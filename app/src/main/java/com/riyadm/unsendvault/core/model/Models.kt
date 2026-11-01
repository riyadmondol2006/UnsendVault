package com.riyadm.unsendvault.core.model

/**
 * Domain models shared by every layer. They are plain Kotlin (no Android types) so parsers, repositories and
 * view models can be unit-tested on the JVM.
 */

/** What a captured message body represents. */
enum class ContentKind {
    /** Normal text exposed by the notification. */
    TEXT,

    /** The app only exposed a placeholder such as "📷 Photo" or "Sent an attachment"; the media itself is never saved. */
    MEDIA_PLACEHOLDER,

    /** Content withheld by the system (Android 15+ sensitive-notification redaction, Android 17 message-read restriction). */
    SYSTEM_REDACTED,

    /** UnsendVault itself replaced the text because the sensitive-content / OTP filter matched. */
    FILTERED_SENSITIVE,
}

/** Lifecycle state of an archived message. Removal of a notification alone never changes this. */
enum class MessageStatus {
    ACTIVE,

    /**
     * Only set when the source app itself published explicit, high-confidence evidence such as
     * "This message was deleted" / "unsent a message" for the same conversation. See [DeletionEvidence].
     */
    POSSIBLY_DELETED,
}

/** Why a message was marked [MessageStatus.POSSIBLY_DELETED]. Stored verbatim so the UI can explain it. */
data class DeletionEvidence(
    /** Adapter id that recognised the marker, e.g. "whatsapp". */
    val adapterId: String,
    /** The marker text the source app displayed (already sanitized, never more than ~120 chars). */
    val markerText: String,
    /** Epoch millis when the marker was observed. */
    val observedAt: Long,
    /** HIGH only; lower-confidence signals are never persisted as evidence. */
    val confidence: Confidence = Confidence.HIGH,
) {
    enum class Confidence { HIGH }
}

/** Android's notification-removal reason, recorded for transparency only (NOT proof of deletion). */
enum class RemovalReason {
    USER_DISMISSED, APP_CANCELLED, GROUP_SUMMARY_CANCELED, CLICKED, LISTENER_CANCELLED, TIMEOUT, CHANNEL_DISABLED,
    PACKAGE_CHANGED, USER_STOPPED, OTHER, UNKNOWN
}

/** A conversation inside one source app (a chat, group, or thread). */
data class Conversation(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val conversationKey: String,
    val title: String,
    val isGroup: Boolean,
    val lastMessageAt: Long,
    val messageCount: Int,
    val lastMessagePreview: String?,
)

/** One archived message as shown in lists. */
data class ArchivedMessage(
    val id: Long,
    val conversationId: Long,
    val packageName: String,
    val appLabel: String,
    val conversationTitle: String,
    val sender: String?,
    val text: String,
    val contentKind: ContentKind,
    /** Timestamp the source app attached to the message (MessagingStyle) or the notification post time. */
    val messageTimestamp: Long,
    val firstCapturedAt: Long,
    val lastUpdatedAt: Long,
    val isFavorite: Boolean,
    val status: MessageStatus,
    val deletionEvidence: DeletionEvidence?,
    /** Number of distinct text versions seen (1 = never edited/updated). */
    val revisionCount: Int,
    /** Last notification-removal reason seen for the notification that carried this message, if any. */
    val lastRemovalReason: RemovalReason?,
    val lastRemovedAt: Long?,
)

/** A previous text version of a message (update history). */
data class MessageRevision(
    val id: Long,
    val messageId: Long,
    val text: String,
    val capturedAt: Long,
)

data class MessageDetail(
    val message: ArchivedMessage,
    val revisions: List<MessageRevision>,
)

/** Per-app aggregate used by the app-summary screen and widget. */
data class AppSummary(
    val packageName: String,
    val appLabel: String,
    val messageCount: Int,
    val conversationCount: Int,
    val lastMessageAt: Long?,
)

data class VaultStats(
    val totalMessages: Int,
    val totalConversations: Int,
    val favorites: Int,
    val possiblyDeleted: Int,
    val apps: Int,
    val oldestCapturedAt: Long?,
    val newestCapturedAt: Long?,
)

/** Filters for archive list / search. All fields are optional and combined with AND. */
data class MessageQuery(
    val text: String? = null,
    val packages: Set<String> = emptySet(),
    val conversationId: Long? = null,
    /** Inclusive epoch-millis range on messageTimestamp. */
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val favoritesOnly: Boolean = false,
    val possiblyDeletedOnly: Boolean = false,
    val limit: Int = 500,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Which apps' notifications are saved. */
enum class AppsToSave {
    /** Any app whose notifications look like chat messages, minus [VaultSettings.excludedPackages] (the default). */
    ALL_CHAT_APPS,

    /** Only [VaultSettings.selectedPackages]; every other app is dropped before anything is read. */
    SELECTED_APPS,
}

/** Which Archive tab the app opens on. */
enum class StartTab { RECENT, CHATS, APPS }

/** All user preferences, persisted with DataStore. Defaults are the privacy-safe choice. */
data class VaultSettings(
    val onboardingCompleted: Boolean = false,
    /** Global pause: when false the listener drops every notification without parsing it. */
    val captureEnabled: Boolean = true,
    /** Packages the user excluded. Checked before any parsing work. */
    val excludedPackages: Set<String> = emptySet(),
    /**
     * When true (default) only notifications that look like messages are archived: MessagingStyle,
     * CATEGORY_MESSAGE, or a package with a dedicated adapter. When false, any app's text notification may be stored.
     */
    val messagesOnly: Boolean = true,
    /** 0 = keep forever. Otherwise messages older than this many days are deleted by the retention job. */
    val retentionDays: Int = 30,
    /** Replace one-time passwords / verification codes with a placeholder before storage. */
    val filterOtp: Boolean = true,
    /** Replace other sensitive patterns (card numbers, IBAN, passwords) before storage. */
    val filterSensitive: Boolean = true,
    val appLockEnabled: Boolean = false,
    /** Seconds the app may stay in background before re-locking. 0 = immediately. */
    val lockTimeoutSeconds: Int = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoledBlack: Boolean = false,
    val dynamicColor: Boolean = true,
    /** Widgets show only counts unless this is enabled; always forced off while the app lock is enabled. */
    val widgetShowContent: Boolean = false,
    /**
     * Android Auto Backup / device transfer is disabled in the manifest (the database key lives in the Android Keystore
     * and cannot leave the device). Encrypted SAF export is the only backup path; these drive the backup controls UI.
     */
    val lastExportAt: Long? = null,
    /** Remind the user to export after this many days without a backup (0 = never remind). */
    val backupReminderDays: Int = 0,
    /** Hide app content in Recents and block screenshots (FLAG_SECURE). */
    val secureScreen: Boolean = true,
    /** Archive tab shown when the app starts. */
    val startTab: StartTab = StartTab.RECENT,
    /**
     * Post UnsendVault's own silent notification when new messages are saved (one per chat, tap opens the chat). Off by
     * default; needs POST_NOTIFICATIONS on Android 13+.
     */
    val savedMessageNotifications: Boolean = false,
    /**
     * Show chat names and message text in those notifications. Off by default (they then only name the app and count);
     * always forced off while the app lock is enabled.
     */
    val notificationShowContent: Boolean = false,
    val appsToSave: AppsToSave = AppsToSave.ALL_CHAT_APPS,
    /** Apps saved in [AppsToSave.SELECTED_APPS] mode. Kept when switching back, so the choice isn't lost. */
    val selectedPackages: Set<String> = emptySet(),
    /**
     * Package names (nothing else) of apps that sent chat-style notifications while not selected, oldest first, at
     * most [MAX_NEW_APPS]. Offered as "New" in the Selected apps list.
     */
    val newApps: List<String> = emptyList(),
) {
    /** Exclusions always apply; in selected mode the app must also be selected. */
    fun savesApp(packageName: String): Boolean =
        packageName !in excludedPackages && (appsToSave == AppsToSave.ALL_CHAT_APPS || packageName in selectedPackages)

    companion object {
        const val MAX_NEW_APPS = 20
    }
}
