package com.riyadm.unsendvault.capture.engine

import com.riyadm.unsendvault.capture.CaptureEngine
import com.riyadm.unsendvault.capture.CaptureLog
import com.riyadm.unsendvault.capture.CaptureStats
import com.riyadm.unsendvault.capture.CaptureTrace
import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.adapter.AppAdapter
import com.riyadm.unsendvault.capture.adapter.AppLabelResolver
import com.riyadm.unsendvault.capture.filter.SensitiveContentFilter
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import com.riyadm.unsendvault.core.model.VaultSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where processed captures go. Production wraps [com.riyadm.unsendvault.data.VaultRepository]. */
interface CaptureSink {
    suspend fun ingest(parsed: ParsedNotification, capturedAt: Long)
    suspend fun recordRemoval(event: RemovalEvent)
}

/**
 * snapshot → settings gates → adapter registry → sensitive filter → [CaptureSink].
 *
 * [onPosted] / [onRemoved] / [onCatchUp] only enqueue into a bounded [CoalescingQueue] so listener callbacks return
 * immediately. Bursts of updates for one notification key (typing indicators, re-posts, "N new messages" counters)
 * collapse into one item within [coalesceWindowMillis]; the latest snapshot wins. A single consumer on [dispatcher]
 * processes items in order; every item is isolated with try/catch so one bad notification never stops the pipeline.
 * The consumer suspends (no polling, no wake lock) while the queue is empty; the only timer is the coalescing window.
 */
class DefaultCaptureEngine(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    /**
     * Settings used for each processed item. Production returns the in-memory snapshot kept fresh from DataStore and
     * falls back to `settings.first()` until the first read completes (never the possibly-default snapshot).
     */
    private val settingsProvider: suspend () -> VaultSettings,
    private val sink: CaptureSink,
    private val registry: AdapterRegistry,
    private val labels: AppLabelResolver,
    private val sensitiveFilter: SensitiveContentFilter,
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
    capacity: Int = DEFAULT_CAPACITY,
    private val coalesceWindowMillis: Long = DEFAULT_COALESCE_WINDOW_MS,
    private val catchUpWindowMillis: Long = DEFAULT_CATCH_UP_WINDOW_MS,
    /** Monotonic milliseconds for the coalescing windows (virtual time in tests). */
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
) : CaptureEngine {

    private val lock = Any()

    /** Guarded by [lock]. */
    private val queue = CoalescingQueue(capacity)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** notificationKey → fingerprint of the last ingested content; skips identical re-posts. Guarded by [lock]. */
    private val lastIngested = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > FINGERPRINT_CACHE_SIZE
    }

    init {
        scope.launch(dispatcher) {
            while (true) {
                val work = synchronized(lock) { queue.next(monotonicClock()) }
                if (work == null) {
                    awaitWork()
                    continue
                }
                CaptureStats.inc(CaptureStats.Counter.ITEMS_PROCESSED)
                try {
                    when (work) {
                        is CoalescingQueue.Work.Posted -> handlePosted(work.snapshot)
                        is CoalescingQueue.Work.Removed -> handleRemoved(work.event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    CaptureLog.d("capture item failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Suspends until new work arrives or the earliest pending item becomes due. */
    private suspend fun awaitWork() {
        val now = monotonicClock()
        val due = synchronized(lock) { queue.nextDueAt(now) }
        when {
            due == null -> wakeups.receive()
            due > now -> withTimeoutOrNull(due - now) { wakeups.receive() }
        }
    }

    override fun onPosted(snapshot: NotificationSnapshot) = enqueuePost(snapshot, coalesceWindowMillis)

    override fun onCatchUp(snapshot: NotificationSnapshot) = enqueuePost(snapshot, catchUpWindowMillis)

    private fun enqueuePost(snapshot: NotificationSnapshot, window: Long) {
        val result = synchronized(lock) { queue.post(snapshot, monotonicClock(), window, ::countDrop) }
        if (result != CoalescingQueue.PostResult.ADDED) CaptureStats.inc(CaptureStats.Counter.QUEUE_COALESCED)
        // A plain merge keeps the due time the consumer is already waiting for: no thread wake-up per update.
        if (result != CoalescingQueue.PostResult.MERGED) wakeups.trySend(Unit)
    }

    override fun onRemoved(event: RemovalEvent) {
        synchronized(lock) { queue.remove(event, ::countDrop) }
        wakeups.trySend(Unit)
    }

    override fun isTracked(notificationKey: String): Boolean =
        synchronized(lock) { lastIngested.containsKey(notificationKey) || queue.isPending(notificationKey) }

    private fun countDrop() = CaptureStats.inc(CaptureStats.Counter.QUEUE_DROPPED_OVERFLOW)

    private suspend fun handlePosted(snapshot: NotificationSnapshot) {
        val settings = settingsProvider()
        // Trace only the synchronous parse: a section must begin and end on the same thread.
        val parsed = CaptureTrace.section("UV:parse") { process(snapshot, settings) } ?: return
        val fingerprint = 31 * parsed.messages.hashCode() + parsed.deletionMarkers.hashCode()
        if (synchronized(lock) { lastIngested[parsed.notificationKey] } == fingerprint) {
            CaptureStats.inc(CaptureStats.Counter.INGEST_SKIPPED_IDENTICAL)
            return
        }
        sink.ingest(parsed, clock())
        CaptureStats.inc(CaptureStats.Counter.INGESTS)
        synchronized(lock) { lastIngested[parsed.notificationKey] = fingerprint }
    }

    private suspend fun handleRemoved(event: RemovalEvent) {
        val settings = settingsProvider()
        if (!settings.captureEnabled || event.packageName == ownPackage || !settings.savesApp(event.packageName)) return
        synchronized(lock) { lastIngested.remove(event.notificationKey) }
        sink.recordRemoval(event)
    }

    /**
     * Pure decision + parse step (no I/O besides the cached label lookup). Returns null when the notification must not
     * be stored: capture paused, own/excluded package, ongoing, rejected by the messages-only gate, or nothing parsed.
     */
    fun process(snapshot: NotificationSnapshot, settings: VaultSettings): ParsedNotification? {
        if (!settings.captureEnabled) return null
        val pkg = snapshot.packageName
        if (pkg == ownPackage || !settings.savesApp(pkg)) return null
        if (snapshot.isOngoing) return null
        val adapter = registry.adapterFor(pkg)
        if (settings.messagesOnly && !safeIsMessage(adapter, snapshot)) return null

        val result = registry.parse(snapshot)
        val parsed = result.outcome as? ParseOutcome.Parsed ?: return null
        val messages = parsed.messages.map { sensitiveFilter.apply(it, settings) }
        if (messages.isEmpty() && parsed.deletionMarkers.isEmpty()) return null
        return ParsedNotification(
            packageName = pkg,
            appLabel = labels.labelFor(pkg),
            notificationKey = snapshot.key,
            postTime = snapshot.postTime,
            adapterId = result.adapterId,
            messages = messages,
            deletionMarkers = parsed.deletionMarkers,
        )
    }

    private fun safeIsMessage(adapter: AppAdapter, s: NotificationSnapshot): Boolean =
        try {
            adapter.isMessageNotification(s)
        } catch (e: Exception) {
            registry.generic.isMessageNotification(s)
        }

    companion object {
        const val DEFAULT_CAPACITY = 256

        /** Updates of one notification within this window are parsed and ingested once (latest wins). */
        const val DEFAULT_COALESCE_WINDOW_MS = 500L

        /** Catch-up items wait longer so live notifications go first. */
        const val DEFAULT_CATCH_UP_WINDOW_MS = 2_000L
        private const val FINGERPRINT_CACHE_SIZE = 256
    }
}
