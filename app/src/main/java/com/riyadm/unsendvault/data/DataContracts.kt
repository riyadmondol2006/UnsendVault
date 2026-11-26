package com.riyadm.unsendvault.data

import android.net.Uri
import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.Conversation
import com.riyadm.unsendvault.core.model.MessageDetail
import com.riyadm.unsendvault.core.model.MediaLink
import com.riyadm.unsendvault.core.model.MessageMedia
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.core.model.VaultStats
import kotlinx.coroutines.flow.Flow

/** Single source of truth for archived messages. Implemented on Room with field-level encryption (see data/security/FieldCipher). */
interface VaultRepository {
    fun observeMessages(query: MessageQuery): Flow<List<ArchivedMessage>>
    fun observeConversations(packages: Set<String> = emptySet()): Flow<List<Conversation>>
    fun observeConversation(id: Long): Flow<Conversation?>
    fun observeMessage(id: Long): Flow<MessageDetail?>
    fun observeAppSummaries(): Flow<List<AppSummary>>
    fun observeStats(): Flow<VaultStats>

    /** Recent messages for widgets (newest first). */
    fun observeRecent(limit: Int): Flow<List<ArchivedMessage>>
    fun observeFavorites(limit: Int): Flow<List<ArchivedMessage>>

    /** Deduplicates, records update history and applies high-confidence deletion markers in one transaction. */
    suspend fun ingest(parsed: ParsedNotification, capturedAt: Long): IngestResult

    /** Records a notification removal for transparency. Never changes [com.riyadm.unsendvault.core.model.MessageStatus]. */
    suspend fun recordRemoval(event: RemovalEvent)

    suspend fun setFavorite(messageId: Long, favorite: Boolean)
    suspend fun deleteMessages(ids: Collection<Long>)
    suspend fun deleteConversation(conversationId: Long)
    suspend fun deleteApp(packageName: String)
    suspend fun deleteAll()

    /** Deletes non-favorite messages whose messageTimestamp is older than [cutoffMillis]. Returns rows deleted. */
    suspend fun deleteOlderThan(cutoffMillis: Long, keepFavorites: Boolean = true): Int

    /** True when a picture is stored for [messageId]. */
    suspend fun hasMedia(messageId: Long): Boolean = false

    /** Encrypts and stores the picture of an archived message. False when the message no longer exists or on failure. */
    suspend fun saveMedia(messageId: Long, mimeType: String, bytes: ByteArray): Boolean = false

    /** The decrypted picture of [messageId], or null. */
    suspend fun loadMedia(messageId: Long): MessageMedia? = null

    /** Links a file on the phone to a media message (encrypted reference). False when the message no longer exists. */
    suspend fun saveMediaLink(messageId: Long, link: MediaLink): Boolean = false

    /** The phone file linked to [messageId], or null. */
    suspend fun loadMediaLink(messageId: Long): MediaLink? = null

    /**
     * Removes the saved picture and the linked file reference of [messageId] (the phone file itself is untouched) and
     * remembers it, so the picture is not copied again from the notification ([isMediaRemoved]).
     */
    suspend fun removeMedia(messageId: Long) {}

    /** True when the user removed the picture/link of [messageId]; automatic capture then skips it. */
    suspend fun isMediaRemoved(messageId: Long): Boolean = false
}

interface SettingsRepository {
    val settings: Flow<VaultSettings>

    /** Synchronous snapshot for the listener hot path; kept up to date from [settings]. */
    val current: VaultSettings
    suspend fun update(transform: (VaultSettings) -> VaultSettings)
}

/** Password-encrypted export/import through the Storage Access Framework. */
interface BackupManager {
    suspend fun export(destination: Uri, password: CharArray): BackupResult
    suspend fun import(source: Uri, password: CharArray, mode: ImportMode = ImportMode.MERGE): BackupResult
}

enum class ImportMode { MERGE, REPLACE }

sealed interface BackupResult {
    data class Success(val messageCount: Int, val conversationCount: Int) : BackupResult
    data class Failure(val reason: Reason, val detail: String? = null) : BackupResult

    enum class Reason { WRONG_PASSWORD, CORRUPT_FILE, UNSUPPORTED_VERSION, IO_ERROR, EMPTY_PASSWORD }
}

/** Schedules/executes retention (auto-delete). Implemented with a daily, constraint-bound WorkManager job. */
interface RetentionScheduler {
    /** (Re)schedules or cancels the periodic job to match [retentionDays] (0 = keep forever = cancel). */
    fun schedule(retentionDays: Int)

    /** Runs retention now; returns rows deleted. */
    suspend fun runNow(): Int
}

/** Asks Glance widgets to refresh. Implementations must debounce and be cheap to call from the capture path. */
fun interface WidgetRefresher {
    fun requestRefresh()
}
