package com.riyadm.unsendvault.capture

import android.annotation.SuppressLint
import android.os.Trace
import com.riyadm.unsendvault.BuildConfig
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Debug-only counters for the capture pipeline, printed by `adb shell dumpsys activity service
 * com.riyadm.unsendvault.debug/com.riyadm.unsendvault.capture.VaultNotificationListener` (see docs/BATTERY.md).
 * Counts only — never package names, keys, titles or text. In release builds [BuildConfig.DEBUG] is false and each
 * increment is a single skipped branch.
 */
object CaptureStats {
    enum class Counter {
        POSTED_CALLBACKS,
        REMOVED_CALLBACKS,
        PREFILTER_OWN_PACKAGE,
        PREFILTER_ONGOING,
        PREFILTER_PAUSED,
        PREFILTER_EXCLUDED,
        PREFILTER_NOT_SELECTED,
        PREFILTER_NON_MESSAGE_CATEGORY,
        PREFILTER_GROUP_SUMMARY,
        PREFILTER_NOT_A_MESSAGE,
        PREFILTER_UNKNOWN_SETTINGS,
        SNAPSHOTS_BUILT,
        REMOVALS_FORWARDED,
        REMOVALS_DROPPED,
        QUEUE_COALESCED,
        QUEUE_DROPPED_OVERFLOW,
        ITEMS_PROCESSED,
        INGESTS,
        INGEST_SKIPPED_IDENTICAL,
        CATCH_UP_RUNS,
        CATCH_UP_DEFERRED,
        CATCH_UP_ITEMS,
        REBIND_REQUESTS,
        REBIND_SKIPPED,
        UNBIND_FOR_PAUSE,
    }

    private val values = AtomicLongArray(Counter.entries.size)

    fun inc(counter: Counter, delta: Long = 1) {
        if (BuildConfig.DEBUG) values.addAndGet(counter.ordinal, delta)
    }

    fun get(counter: Counter): Long = values.get(counter.ordinal)

    fun reset() {
        for (i in 0 until values.length()) values.set(i, 0)
    }

    fun dump(writer: PrintWriter) {
        if (!BuildConfig.DEBUG) {
            writer.println("UnsendVault capture counters: release build (counters compiled out)")
            return
        }
        writer.println("UnsendVault capture counters (since process start):")
        for (c in Counter.entries) writer.println("  ${c.name.lowercase()}=${values.get(c.ordinal)}")
    }
}

/**
 * android.os.Trace sections ("UV:*") for Perfetto/systrace. No-ops unless tracing is active; never include content in
 * section names. JVM unit tests run with returnDefaultValues, so the calls are harmless there too.
 */
internal object CaptureTrace {
    inline fun <T> section(name: String, block: () -> T): T {
        begin(name)
        try {
            return block()
        } finally {
            end()
        }
    }

    /** Always paired with [end] by the caller (try/finally); lint can't see across the two methods. */
    @SuppressLint("UnclosedTrace")
    @PublishedApi
    internal fun begin(name: String) {
        try {
            Trace.beginSection(name)
        } catch (e: RuntimeException) {
            // Not available (plain JVM).
        }
    }

    @PublishedApi
    internal fun end() {
        try {
            Trace.endSection()
        } catch (e: RuntimeException) {
            // Not available (plain JVM).
        }
    }
}
