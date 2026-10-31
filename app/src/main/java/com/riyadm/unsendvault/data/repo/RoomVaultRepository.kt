package com.riyadm.unsendvault.data.repo

import android.database.SQLException
import android.util.Log
import androidx.room.withTransaction
import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.DeletionMarker
import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.MessageDetail
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.core.model.VaultStats
import com.riyadm.unsendvault.data.ImportMode
import com.riyadm.unsendvault.data.VaultRepository
import com.riyadm.unsendvault.data.backup.BackupConversation
import com.riyadm.unsendvault.data.backup.BackupEvidence
import com.riyadm.unsendvault.data.backup.BackupFormatException
import com.riyadm.unsendvault.data.backup.BackupMessage
import com.riyadm.unsendvault.data.backup.BackupPayload
import com.riyadm.unsendvault.data.backup.BackupRevision
import com.riyadm.unsendvault.data.backup.BackupStore
import com.riyadm.unsendvault.data.backup.ImportCounts
import com.riyadm.unsendvault.data.db.ConversationEntity
import com.riyadm.unsendvault.data.db.MessageEntity
import com.riyadm.unsendvault.data.db.MessageRevisionEntity
import com.riyadm.unsendvault.data.db.VaultDao
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.FieldCipher.Field
import com.riyadm.unsendvault.data.security.FieldCipher.IndexDomain
import com.riyadm.unsendvault.data.security.VaultDatabaseHolder
import com.riyadm.unsendvault.data.security.VaultHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Room implementation of [VaultRepository] with field-level encryption ([FieldCipher], keys held by the open
 * [VaultDatabase]): every content value is encrypted before it is written and decrypted after it is read, on
 * Dispatchers.Default for Flows.
 *
 * Reads are Flows that re-query on table invalidation. While the vault cannot be opened (see [VaultHealth.keyState])
 * reads emit empty values and writes are no-ops, so a Keystore problem never crashes the listener or the UI.
 *
 * Message identity (shared by [ingest] and backup MERGE), compared through blind indexes of the same plaintext values
 * as before encryption (equal plaintext <=> equal index):
 *  - precise timestamp (MessagingStyle): (conversation, sender, message_timestamp).
 *  - otherwise: (conversation, sender, normalized text) with message_timestamp within ±[IMPRECISE_WINDOW_MS].
 */
class RoomVaultRepository(
    private val holder: VaultDatabaseHolder,
) : VaultRepository, VaultHealth by holder, BackupStore {

    constructor(db: VaultDatabase) : this(VaultDatabaseHolder.of(db))

    // ---- Reads -----------------------------------------------------------------------------------------------------

    private fun <T> observe(empty: T, query: (VaultDatabase) -> Flow<T>): Flow<T> =
        holder.databases.flatMapLatest { db ->
            if (db == null) {
                flowOf(empty)
            } else {
                query(db).flowOn(Dispatchers.Default).catch { e ->
                    // A reset closes the old database while a query may still be in flight.
                    if (e is IllegalStateException || e is SQLException) {
                        Log.w(TAG, "Query failed", e)
                        emit(empty)
                    } else {
                        throw e
                    }
                }
            }
        }

    /**
     * Without text: one SQL query (filters + LIMIT) re-run by Room on invalidation, rows decrypted after loading.
     * With text: [MessageSearch] over decrypted rows, re-run whenever messages or conversations change. conflate()
     * rather than mapLatest: a burst of captures cannot keep restarting (and starving) a long scan; at worst one result
     * one capture behind is shown before the re-run.
     */
    override fun observeMessages(query: MessageQuery): Flow<List<ArchivedMessage>> =
        observe(emptyList()) { db ->
            val dao = db.dao()
            val cipher = db.cipher
            if (SearchText.terms(query.text).isEmpty()) {
                dao.observeMessageRows(SearchSql.build(query).toRoomRawQuery()).map { rows -> rows.map { it.toDomain(cipher) } }
            } else {
                db.invalidationTracker.createFlow(MESSAGES, CONVERSATIONS)
                    .conflate()
                    .map { MessageSearch(cipher).run(dao, query) }
            }
        }

    override fun observeConversations(packages: Set<String>): Flow<List<Conversation>> =
        observe(emptyList()) { db ->
            val dao = db.dao()
            val source = if (packages.isEmpty()) dao.observeConversations() else dao.observeConversationsIn(packages)
            source.map { list -> list.map { it.toDomain(db.cipher) } }
        }

    override fun observeConversation(id: Long): Flow<Conversation?> =
        observe(null) { db -> db.dao().observeConversation(id).map { it?.toDomain(db.cipher) } }

    override fun observeMessage(id: Long): Flow<MessageDetail?> =
        observe(null) { db ->
            val dao = db.dao()
            combine(dao.observeMessageRow(id), dao.observeRevisions(id)) { row, revisions ->
                row?.let { MessageDetail(it.toDomain(db.cipher), revisions.map { r -> r.toDomain(db.cipher) }) }
            }
        }

    override fun observeAppSummaries(): Flow<List<AppSummary>> =
        observe(emptyList()) { db -> db.dao().observeAppSummaries().map { list -> list.map { it.toDomain() } } }

    override fun observeStats(): Flow<VaultStats> =
        observe(EMPTY_STATS) { db -> db.dao().observeStats().map { it.toDomain() } }

    override fun observeRecent(limit: Int): Flow<List<ArchivedMessage>> =
        observe(emptyList()) { db ->
            db.dao().observeRecent(limit.coerceAtLeast(0)).map { rows -> rows.map { it.toDomain(db.cipher) } }
        }

    override fun observeFavorites(limit: Int): Flow<List<ArchivedMessage>> =
        observe(emptyList()) { db ->
            db.dao().observeFavorites(limit.coerceAtLeast(0)).map { rows -> rows.map { it.toDomain(db.cipher) } }
        }

    // ---- Ingest ----------------------------------------------------------------------------------------------------

    override suspend fun ingest(parsed: ParsedNotification, capturedAt: Long): IngestResult {
        if (parsed.messages.isEmpty() && parsed.deletionMarkers.isEmpty()) return IngestResult.EMPTY
        val db = holder.database() ?: return IngestResult.EMPTY
        val cipher = db.cipher
        return db.withTransaction {
            val dao = db.dao()
            val counter = Counter()
            val touched = mutableSetOf<Long>()
            val saved = ArrayList<IngestResult.SavedRef>()
            val markedIn = HashSet<Long>()
            val notificationIndex = cipher.blindIndex(parsed.notificationKey, IndexDomain.NOTIFICATION)
            val conversations = ConversationCache(dao, cipher)

            // Text of every marker in this notification, per conversation: a captured "message" carrying exactly the
            // marker text is the marker itself and must never be archived as / written over a message.
            val markerTexts = parsed.deletionMarkers
                .groupBy({ it.packageName to it.conversationKey }, { TextNormalizer.normalize(it.markerText) })

            val batch = BatchContext(parsed.messages, cipher)
            for (c in parsed.messages) {
                val normalized = TextNormalizer.normalize(c.text)
                if (normalized.isEmpty()) continue
                if (markerTexts[c.packageName to c.conversationKey]?.contains(normalized) == true) continue
                val conversation = conversations.upsert(c, parsed.appLabel, capturedAt)
                when (ingestMessage(dao, cipher, conversation.id, c, normalized, notificationIndex, capturedAt, batch)) {
                    Outcome.INSERTED -> {
                        counter.inserted++
                        touched += conversation.id
                        saved += IngestResult.SavedRef(conversation.id, c)
                    }
                    Outcome.UPDATED -> { counter.updated++; touched += conversation.id }
                    Outcome.UNCHANGED -> counter.unchanged++
                }
            }
            for (marker in parsed.deletionMarkers) {
                val conversation = conversations.find(marker.packageName, marker.conversationKey) ?: continue
                if (applyMarker(dao, cipher, conversation.id, marker, capturedAt)) {
                    counter.marked++
                    markedIn += conversation.id
                }
            }
            if (touched.isNotEmpty()) recomputeAggregates(dao, cipher, touched.toList())
            IngestResult(counter.inserted, counter.updated, counter.unchanged, counter.marked, saved, markedIn)
        }
    }

    private class Counter {
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var marked = 0
    }

    private enum class Outcome { INSERTED, UPDATED, UNCHANGED }

    /**
     * What the rest of the notification says, used to keep identity matching honest:
     * - rows already matched by an earlier message of this notification are never matched twice, so a sender who
     *   really sent "ok" twice keeps two rows;
     * - several messages sharing one (sender, timestamp) slot are distinct messages, never an edit of each other.
     * Stored rows are compared through their text index, so the batch's texts are kept as indexes too.
     */
    private class BatchContext(messages: List<CapturedMessage>, cipher: FieldCipher) {
        val matchedIds = mutableSetOf<Long>()
        private val slotCounts = messages.filter { it.hasPreciseTimestamp }
            .groupingBy { Triple(it.conversationKey, TextNormalizer.senderKey(it.sender), it.messageTimestamp) }.eachCount()
        private val textIndexesBySender = messages
            .groupBy(
                { it.conversationKey to TextNormalizer.senderKey(it.sender) },
                { cipher.blindIndex(TextNormalizer.normalize(it.text), IndexDomain.TEXT) },
            )
            .mapValues { it.value.toSet() }

        fun slotCount(c: CapturedMessage, senderKey: String) = slotCounts[Triple(c.conversationKey, senderKey, c.messageTimestamp)] ?: 0

        fun senderStillShows(c: CapturedMessage, senderKey: String, textIndex: String) =
            textIndexesBySender[c.conversationKey to senderKey]?.contains(textIndex) == true
    }

    /** Conversation lookups/upserts for one ingest; decrypts each stored title at most once. */
    private class ConversationCache(private val dao: VaultDao, private val cipher: FieldCipher) {
        private val known = HashMap<Pair<String, String>, Pair<ConversationEntity, String>>()

        private fun indexOf(key: String) = cipher.blindIndex(key, IndexDomain.CONVERSATION)

        suspend fun find(packageName: String, conversationKey: String): ConversationEntity? =
            known[packageName to conversationKey]?.first ?: dao.findConversation(packageName, indexOf(conversationKey))

        suspend fun upsert(c: CapturedMessage, appLabel: String, now: Long): ConversationEntity {
            val cacheKey = c.packageName to c.conversationKey
            val cached = known[cacheKey]
            val existing = cached?.first ?: dao.findConversation(c.packageName, indexOf(c.conversationKey))
            if (existing == null) {
                val index = indexOf(c.conversationKey)
                val owner = FieldCipher.forConversation(c.packageName, index)
                val title = c.conversationTitle.ifBlank { c.sender ?: c.conversationKey }
                val entity = ConversationEntity(
                    packageName = c.packageName,
                    appLabel = appLabel.ifBlank { c.packageName },
                    conversationIndex = index,
                    conversationKey = cipher.encrypt(c.conversationKey, Field.CONVERSATION_KEY, owner),
                    title = cipher.encrypt(title, Field.CONVERSATION_TITLE, owner),
                    isGroup = c.isGroup,
                    createdAt = now,
                    lastMessageAt = c.messageTimestamp,
                    lastMessagePreview = null,
                    messageCount = 0,
                )
                return entity.copy(id = dao.insertConversation(entity)).also { known[cacheKey] = it to title }
            }
            val existingTitle = cached?.second ?: cipher.decrypt(existing.title, Field.CONVERSATION_TITLE, existing.binding())
            val title = c.conversationTitle.ifBlank { existingTitle }
            val label = appLabel.ifBlank { existing.appLabel }
            if (title != existingTitle || c.isGroup != existing.isGroup || label != existing.appLabel) {
                val encryptedTitle = if (title == existingTitle) {
                    existing.title
                } else {
                    cipher.encrypt(title, Field.CONVERSATION_TITLE, existing.binding())
                }
                dao.updateConversationMeta(existing.id, encryptedTitle, c.isGroup, label)
                val updated = existing.copy(title = encryptedTitle, isGroup = c.isGroup, appLabel = label)
                known[cacheKey] = updated to title
                return updated
            }
            known[cacheKey] = existing to existingTitle
            return existing
        }
    }

    private suspend fun ingestMessage(
        dao: VaultDao,
        cipher: FieldCipher,
        conversationId: Long,
        c: CapturedMessage,
        normalized: String,
        notificationIndex: String,
        capturedAt: Long,
        batch: BatchContext,
    ): Outcome {
        val senderKey = TextNormalizer.senderKey(c.sender)
        val senderIndex = cipher.blindIndex(senderKey, IndexDomain.SENDER)
        val textIndex = cipher.blindIndex(normalized, IndexDomain.TEXT)
        val owner = FieldCipher.forMessage(conversationId)
        val existing = findIdentity(dao, conversationId, senderKey, senderIndex, textIndex, c, batch)
        existing?.let { batch.matchedIds += it.id }
        if (existing == null) {
            batch.matchedIds += dao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    packageName = c.packageName,
                    sender = cipher.encryptOrNull(c.sender, Field.MESSAGE_SENDER, owner),
                    senderIndex = senderIndex,
                    text = cipher.encrypt(c.text, Field.MESSAGE_TEXT, owner),
                    textIndex = textIndex,
                    contentKind = c.contentKind.name,
                    messageTimestamp = c.messageTimestamp,
                    hasPreciseTimestamp = c.hasPreciseTimestamp,
                    firstCapturedAt = capturedAt,
                    lastUpdatedAt = capturedAt,
                    isFavorite = false,
                    status = MessageStatus.ACTIVE.name,
                    deletionAdapterId = null,
                    deletionMarkerText = null,
                    deletionObservedAt = null,
                    revisionCount = 1,
                    lastNotificationIndex = notificationIndex,
                ),
            )
            return Outcome.INSERTED
        }

        val existingKind = contentKindOf(existing.contentKind)
        val upgradeToPrecise = c.hasPreciseTimestamp && !existing.hasPreciseTimestamp
        val sameText = existing.textIndex == textIndex
        val updated: MessageEntity? = when {
            // Placeholders never overwrite anything (real text or another placeholder).
            c.contentKind.isWithheld() -> null
            sameText -> null
            // After a deletion marker the app may re-post the slot with its "deleted" text; keep the original.
            existing.status == MessageStatus.POSSIBLY_DELETED.name -> null
            // A placeholder is replaced by the real text; the placeholder is not a "version", so no revision.
            existingKind.isWithheld() -> existing.copy(
                text = cipher.encrypt(c.text, Field.MESSAGE_TEXT, owner),
                textIndex = textIndex,
                contentKind = c.contentKind.name,
                lastUpdatedAt = capturedAt,
            )
            // Same identity, different text: the app updated/edited the message. Keep history.
            else -> {
                dao.insertRevision(
                    MessageRevisionEntity(
                        messageId = existing.id,
                        text = cipher.encrypt(
                            cipher.decrypt(existing.text, Field.MESSAGE_TEXT, owner),
                            Field.REVISION_TEXT,
                            FieldCipher.forRevision(existing.id),
                        ),
                        contentKind = existing.contentKind,
                        capturedAt = existing.lastUpdatedAt,
                    ),
                )
                existing.copy(
                    text = cipher.encrypt(c.text, Field.MESSAGE_TEXT, owner),
                    textIndex = textIndex,
                    contentKind = c.contentKind.name,
                    lastUpdatedAt = capturedAt,
                    revisionCount = existing.revisionCount + 1,
                )
            }
        }
        if (updated != null) {
            dao.updateMessage(
                updated.copy(
                    lastNotificationIndex = notificationIndex,
                    messageTimestamp = if (upgradeToPrecise) c.messageTimestamp else updated.messageTimestamp,
                    hasPreciseTimestamp = updated.hasPreciseTimestamp || upgradeToPrecise,
                ),
            )
            return Outcome.UPDATED
        }
        if (upgradeToPrecise) {
            dao.updateMessage(
                existing.copy(
                    messageTimestamp = c.messageTimestamp,
                    hasPreciseTimestamp = true,
                    lastNotificationIndex = notificationIndex,
                ),
            )
        } else if (existing.lastNotificationIndex != notificationIndex) {
            dao.updateNotificationIndex(existing.id, notificationIndex)
        }
        return Outcome.UNCHANGED
    }

    /**
     * Finds the stored row this captured message is a copy of, or null for a new message.
     *
     * 1. Same sender and normalized text within ±[IMPRECISE_WINDOW_MS], closest timestamp first, skipping rows already
     *    claimed by this notification. This covers re-posts of the same history (exact timestamp), apps that re-stamp
     *    old messages on every update, and upgrading a record first seen without a MessagingStyle timestamp.
     * 2. Precise only: the same (sender, timestamp) slot with different text is an edit, but only when that slot holds
     *    exactly one message both in this notification and in the archive, and the stored text is no longer shown for
     *    that sender. Otherwise two different messages that share a timestamp would be merged into a false "edit".
     */
    private suspend fun findIdentity(
        dao: VaultDao,
        conversationId: Long,
        senderKey: String,
        senderIndex: String,
        textIndex: String,
        c: CapturedMessage,
        batch: BatchContext,
    ): MessageEntity? {
        val timestamp = c.messageTimestamp
        dao.findSameTextNear(
            conversationId, senderIndex, textIndex, timestamp, timestamp - IMPRECISE_WINDOW_MS, timestamp + IMPRECISE_WINDOW_MS,
        ).firstOrNull { it.id !in batch.matchedIds }?.let { return it }

        if (!c.hasPreciseTimestamp || batch.slotCount(c, senderKey) != 1) return null
        if (dao.countAtPreciseIdentity(conversationId, senderIndex, timestamp) != 1) return null
        return dao.findByPreciseIdentity(conversationId, senderIndex, timestamp)
            ?.takeIf { it.id !in batch.matchedIds && !batch.senderStillShows(c, senderKey, it.textIndex) }
    }

    /**
     * High-confidence deletion marker handling. Marks at most one message and only when:
     *  (a) a message has the exact identity (conversation, marker sender, marker timestamp), or
     *  (b) the marker names a sender and exactly one ACTIVE message from that sender exists in the 15 minutes up to
     *      the marker.
     * Anything ambiguous is ignored. The original text is kept; the marker is stored (encrypted) only as evidence.
     */
    private suspend fun applyMarker(
        dao: VaultDao,
        cipher: FieldCipher,
        conversationId: Long,
        marker: DeletionMarker,
        observedAt: Long,
    ): Boolean {
        val senderKey = TextNormalizer.senderKey(marker.sender)
        val senderIndex = cipher.blindIndex(senderKey, IndexDomain.SENDER)
        val markerIndex = cipher.blindIndex(TextNormalizer.normalize(marker.markerText), IndexDomain.TEXT)
        val evidenceText = TextNormalizer.truncate(marker.markerText.trim(), MARKER_TEXT_MAX)

        val exact = dao.findByPreciseIdentity(conversationId, senderIndex, marker.markerTimestamp)
            ?.takeIf { it.textIndex != markerIndex }
        val target = exact ?: if (senderKey.isNotEmpty()) {
            dao.activeFromSenderBetween(
                conversationId, senderIndex, marker.markerTimestamp - MARKER_WINDOW_MS, marker.markerTimestamp, limit = 3,
            ).filter { it.textIndex != markerIndex }.singleOrNull()
        } else {
            null
        }
        target ?: return false
        val encrypted = cipher.encrypt(evidenceText, Field.MESSAGE_MARKER, FieldCipher.forMessage(conversationId))
        return dao.markPossiblyDeleted(target.id, marker.adapterId, encrypted, observedAt) > 0
    }

    /** message_count / last_message_at in SQL, then the encrypted preview re-encrypted from the newest message. */
    private suspend fun recomputeAggregates(dao: VaultDao, cipher: FieldCipher, ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach { chunk ->
            dao.recomputeCounts(chunk)
            refreshPreviews(dao, cipher, chunk)
        }
    }

    private suspend fun recomputeAllAggregates(dao: VaultDao, cipher: FieldCipher) {
        dao.recomputeAllCounts()
        dao.allConversationIds().chunked(SQL_CHUNK).forEach { refreshPreviews(dao, cipher, it) }
    }

    private suspend fun refreshPreviews(dao: VaultDao, cipher: FieldCipher, ids: List<Long>) {
        for (row in dao.latestTexts(ids)) {
            val preview = row.text?.let { blob ->
                val text = cipher.decrypt(blob, Field.MESSAGE_TEXT, FieldCipher.forMessage(row.id))
                cipher.encrypt(text, Field.CONVERSATION_PREVIEW, FieldCipher.forConversation(row.packageName, row.conversationIndex))
            }
            dao.setPreview(row.id, preview)
        }
    }

    override suspend fun recordRemoval(event: RemovalEvent) {
        val db = holder.database() ?: return
        db.dao().recordRemoval(db.cipher.blindIndex(event.notificationKey, IndexDomain.NOTIFICATION), event.reason.name, event.removedAt)
    }

    // ---- User actions ----------------------------------------------------------------------------------------------

    override suspend fun setFavorite(messageId: Long, favorite: Boolean) {
        holder.database()?.dao()?.setFavorite(messageId, favorite)
    }

    override suspend fun deleteMessages(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = holder.database() ?: return
        db.withTransaction {
            val dao = db.dao()
            val conversations = mutableSetOf<Long>()
            ids.distinct().chunked(SQL_CHUNK).forEach { chunk ->
                conversations += dao.conversationIdsOf(chunk)
                dao.deleteMessages(chunk)
            }
            recomputeAggregates(dao, db.cipher, conversations.toList())
            dao.deleteEmptyConversations()
        }
    }

    override suspend fun deleteConversation(conversationId: Long) {
        holder.database()?.dao()?.deleteConversation(conversationId)
    }

    override suspend fun deleteApp(packageName: String) {
        holder.database()?.dao()?.deleteConversationsForPackage(packageName)
    }

    override suspend fun deleteAll() {
        holder.database()?.dao()?.deleteAllConversations()
    }

    override suspend fun deleteOlderThan(cutoffMillis: Long, keepFavorites: Boolean): Int {
        val db = holder.database() ?: return 0
        return db.withTransaction {
            val dao = db.dao()
            val deleted = dao.deleteOlderThan(cutoffMillis, keepFavorites)
            if (deleted > 0) {
                dao.deleteEmptyConversations()
                recomputeAllAggregates(dao, db.cipher)
            }
            deleted
        }
    }

    // ---- Backup ----------------------------------------------------------------------------------------------------

    /** Decrypts the whole archive into the (unchanged) backup DTOs; BackupCrypto then encrypts the file. */
    override suspend fun exportSnapshot(exportedAt: Long): BackupPayload? {
        val db = holder.database() ?: return null
        val cipher = db.cipher
        return db.withTransaction {
            val dao = db.dao()
            val conversations = dao.allConversations()
            val revisions = dao.allRevisions().groupBy { it.messageId }
            val messages = dao.allMessages().map { m ->
                val owner = FieldCipher.forMessage(m.conversationId)
                val marker = m.deletionMarkerText
                BackupMessage(
                    conversationRef = m.conversationId,
                    sender = cipher.decryptNullable(m.sender, Field.MESSAGE_SENDER, owner),
                    text = cipher.decrypt(m.text, Field.MESSAGE_TEXT, owner),
                    contentKind = m.contentKind,
                    messageTimestamp = m.messageTimestamp,
                    hasPreciseTimestamp = m.hasPreciseTimestamp,
                    firstCapturedAt = m.firstCapturedAt,
                    lastUpdatedAt = m.lastUpdatedAt,
                    isFavorite = m.isFavorite,
                    status = m.status,
                    deletionEvidence = if (m.deletionAdapterId != null && marker != null && m.deletionObservedAt != null) {
                        BackupEvidence(m.deletionAdapterId, cipher.decrypt(marker, Field.MESSAGE_MARKER, owner), m.deletionObservedAt)
                    } else {
                        null
                    },
                    revisionCount = m.revisionCount,
                    lastRemovalReason = m.lastRemovalReason,
                    lastRemovedAt = m.lastRemovedAt,
                    revisions = revisions[m.id].orEmpty().map { BackupRevision(it.toDomain(cipher).text, it.contentKind, it.capturedAt) },
                )
            }
            BackupPayload(
                exportedAt = exportedAt,
                conversations = conversations.map {
                    val owner = it.binding()
                    BackupConversation(
                        it.id,
                        it.packageName,
                        it.appLabel,
                        cipher.decrypt(it.conversationKey, Field.CONVERSATION_KEY, owner),
                        cipher.decrypt(it.title, Field.CONVERSATION_TITLE, owner),
                        it.isGroup,
                        it.createdAt,
                    )
                },
                messages = messages,
            )
        }
    }

    override suspend fun importPayload(payload: BackupPayload, mode: ImportMode): ImportCounts? {
        val refs = payload.conversations.map { it.ref }
        if (refs.toSet().size != refs.size) throw BackupFormatException("Duplicate conversation reference")
        val known = refs.toSet()
        payload.messages.firstOrNull { it.conversationRef !in known }?.let {
            throw BackupFormatException("Message references unknown conversation ${it.conversationRef}")
        }
        val db = holder.database() ?: return null
        val cipher = db.cipher
        return db.withTransaction {
            val dao = db.dao()
            if (mode == ImportMode.REPLACE) dao.deleteAllConversations()
            val idByRef = HashMap<Long, Long>(payload.conversations.size * 2)
            for (c in payload.conversations) {
                val index = cipher.blindIndex(c.conversationKey, IndexDomain.CONVERSATION)
                val owner = FieldCipher.forConversation(c.packageName, index)
                val existing = dao.findConversation(c.packageName, index)
                idByRef[c.ref] = existing?.id ?: dao.insertConversation(
                    ConversationEntity(
                        packageName = c.packageName,
                        appLabel = c.appLabel.ifBlank { c.packageName },
                        conversationIndex = index,
                        conversationKey = cipher.encrypt(c.conversationKey, Field.CONVERSATION_KEY, owner),
                        title = cipher.encrypt(c.title, Field.CONVERSATION_TITLE, owner),
                        isGroup = c.isGroup,
                        createdAt = c.createdAt,
                        lastMessageAt = 0,
                        lastMessagePreview = null,
                        messageCount = 0,
                    ),
                )
            }
            val packageByRef = payload.conversations.associate { it.ref to it.packageName }
            val claimed = mutableSetOf<Long>()
            for (m in payload.messages) {
                mergeMessage(dao, cipher, idByRef.getValue(m.conversationRef), packageByRef.getValue(m.conversationRef), m, claimed)
            }
            recomputeAllAggregates(dao, cipher)
            dao.deleteEmptyConversations()
            ImportCounts(messages = payload.messages.size, conversations = payload.conversations.size)
        }
    }

    /**
     * Backup rows are finished records: an imported row matches an exact twin (same sender, text and timestamp) not
     * already claimed by this import, else the single archived row in the same precise (sender, timestamp) slot, whose
     * versions are then merged. Re-importing a file is idempotent and distinct rows always stay distinct.
     */
    private suspend fun mergeMessage(
        dao: VaultDao,
        cipher: FieldCipher,
        conversationId: Long,
        packageName: String,
        m: BackupMessage,
        claimed: MutableSet<Long>,
    ) {
        val normalized = TextNormalizer.normalize(m.text)
        val senderIndex = cipher.blindIndex(TextNormalizer.senderKey(m.sender), IndexDomain.SENDER)
        val textIndex = cipher.blindIndex(normalized, IndexDomain.TEXT)
        val owner = FieldCipher.forMessage(conversationId)
        val ts = m.messageTimestamp
        val existing = dao.findSameTextNear(conversationId, senderIndex, textIndex, ts, ts, ts).firstOrNull { it.id !in claimed }
            ?: if (m.hasPreciseTimestamp && dao.countAtPreciseIdentity(conversationId, senderIndex, ts) == 1) {
                // The archive holds one version of this exact message slot (e.g. edited later): merge versions.
                dao.findByPreciseIdentity(conversationId, senderIndex, ts)?.takeIf { it.id !in claimed }
            } else {
                null
            }
        existing?.let { claimed += it.id }
        val evidence = m.deletionEvidence?.takeIf { m.status == MessageStatus.POSSIBLY_DELETED.name }
        val kind = contentKindOf(m.contentKind)
        fun encryptedMarker(text: String) = cipher.encrypt(TextNormalizer.truncate(text, MARKER_TEXT_MAX), Field.MESSAGE_MARKER, owner)
        fun encryptedRevision(messageId: Long, text: String, contentKind: String?, capturedAt: Long) = MessageRevisionEntity(
            messageId = messageId,
            text = cipher.encrypt(text, Field.REVISION_TEXT, FieldCipher.forRevision(messageId)),
            contentKind = contentKindOf(contentKind).name,
            capturedAt = capturedAt,
        )
        if (existing == null) {
            val id = dao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    packageName = packageName,
                    sender = cipher.encryptOrNull(m.sender, Field.MESSAGE_SENDER, owner),
                    senderIndex = senderIndex,
                    text = cipher.encrypt(m.text, Field.MESSAGE_TEXT, owner),
                    textIndex = textIndex,
                    contentKind = kind.name,
                    messageTimestamp = m.messageTimestamp,
                    hasPreciseTimestamp = m.hasPreciseTimestamp,
                    firstCapturedAt = m.firstCapturedAt,
                    lastUpdatedAt = m.lastUpdatedAt,
                    isFavorite = m.isFavorite,
                    status = if (evidence != null) MessageStatus.POSSIBLY_DELETED.name else MessageStatus.ACTIVE.name,
                    deletionAdapterId = evidence?.adapterId,
                    deletionMarkerText = evidence?.let { encryptedMarker(it.markerText) },
                    deletionObservedAt = evidence?.observedAt,
                    revisionCount = 1 + m.revisions.size,
                    lastRemovalReason = m.lastRemovalReason,
                    lastRemovedAt = m.lastRemovedAt,
                ),
            )
            claimed += id
            val seen = HashSet<Pair<String, Long>>()
            m.revisions.forEach { r ->
                if (seen.add(r.text to r.capturedAt)) dao.insertRevision(encryptedRevision(id, r.text, r.contentKind, r.capturedAt))
            }
            return
        }

        // Merge into the existing message. Revisions are keyed by (text, capturedAt), which makes re-imports no-ops.
        var merged = existing
        var mergedText = cipher.decrypt(existing.text, Field.MESSAGE_TEXT, owner)
        val existingRevisions = dao.revisionsFor(existing.id)
        val knownVersions = existingRevisions.mapTo(HashSet()) { it.toDomain(cipher).text to it.capturedAt }
        val candidates = mutableListOf<BackupRevision>()
        if (textIndex != existing.textIndex) {
            if (m.lastUpdatedAt > existing.lastUpdatedAt && !kind.isWithheld()) {
                candidates += BackupRevision(mergedText, existing.contentKind, existing.lastUpdatedAt)
                merged = merged.copy(
                    text = cipher.encrypt(m.text, Field.MESSAGE_TEXT, owner),
                    textIndex = textIndex,
                    contentKind = kind.name,
                    lastUpdatedAt = m.lastUpdatedAt,
                )
                mergedText = m.text
            } else if (!kind.isWithheld()) {
                candidates += BackupRevision(m.text, kind.name, m.lastUpdatedAt)
            }
        }
        candidates += m.revisions
        var added = 0
        for (r in candidates) {
            val key = r.text to r.capturedAt
            if (r.text == mergedText || !knownVersions.add(key)) continue
            dao.insertRevision(encryptedRevision(existing.id, r.text, r.contentKind, r.capturedAt))
            added++
        }
        merged = merged.copy(
            isFavorite = existing.isFavorite || m.isFavorite,
            firstCapturedAt = minOf(existing.firstCapturedAt, m.firstCapturedAt),
            revisionCount = if (added > 0) 1 + existingRevisions.size + added else existing.revisionCount,
        )
        if (evidence != null && existing.status == MessageStatus.ACTIVE.name) {
            merged = merged.copy(
                status = MessageStatus.POSSIBLY_DELETED.name,
                deletionAdapterId = evidence.adapterId,
                deletionMarkerText = encryptedMarker(evidence.markerText),
                deletionObservedAt = evidence.observedAt,
            )
        }
        if (existing.lastRemovedAt == null && m.lastRemovedAt != null) {
            merged = merged.copy(lastRemovalReason = m.lastRemovalReason, lastRemovedAt = m.lastRemovedAt)
        }
        // Array fields compare by reference, so this is "anything replaced or changed".
        if (merged != existing) dao.updateMessage(merged)
    }

    companion object {
        private const val TAG = "UvVaultRepo"
        const val IMPRECISE_WINDOW_MS = 10 * 60 * 1000L
        const val MARKER_WINDOW_MS = 15 * 60 * 1000L
        const val MARKER_TEXT_MAX = 120
        private const val SQL_CHUNK = 500
        private const val MESSAGES = "messages"
        private const val CONVERSATIONS = "conversations"
        private val EMPTY_STATS = VaultStats(0, 0, 0, 0, 0, null, null)
    }
}

/** The vault's health/recovery API, or null for implementations without encryption (fakes). */
val VaultRepository.vaultHealth: VaultHealth?
    get() = this as? VaultHealth
