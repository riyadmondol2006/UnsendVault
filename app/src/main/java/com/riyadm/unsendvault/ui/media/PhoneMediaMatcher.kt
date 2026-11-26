package com.riyadm.unsendvault.ui.media

import kotlin.math.abs

enum class PhoneMediaKind { IMAGE, VIDEO, AUDIO }

/** One media file on the phone, as read from MediaStore. [path] is the folder (RELATIVE_PATH, or DATA before API 29). */
data class PhoneMediaFile(
    val uri: String,
    val kind: PhoneMediaKind,
    val mimeType: String,
    /** MediaStore DATE_ADDED in milliseconds: when the chat app saved (downloaded) the file. */
    val addedAt: Long,
    val path: String,
    val ownerPackage: String?,
    val name: String,
)

/**
 * Decides which phone files are offered for a media message in "Find on phone". Pure Kotlin.
 *
 * It never picks a file by itself: a notification carries no file name or id, so the time a file was saved is only a
 * hint (several pictures can arrive within seconds, downloads can be delayed). The user always confirms. To keep the
 * list short and relevant:
 * - only files saved from [BEFORE_MS] before to [AFTER_MS] after the message (auto-download can wait for Wi-Fi);
 * - only files in the chat app's own folders (or owned by its package), unless the user asks for all apps; files of
 *   another known chat app (e.g. WhatsApp Business vs WhatsApp) never count as the app's own;
 * - never the user's own sent files or status downloads;
 * - the expected kind first (a "📷 Photo" message lists pictures before videos), then closest in time.
 */
object PhoneMediaMatcher {
    const val BEFORE_MS = 5 * 60_000L
    const val AFTER_MS = 48 * 60 * 60_000L
    const val MAX_RESULTS = 60

    /** Folder names chat apps use, by package (besides the package name itself and the app label). */
    private val KNOWN_FOLDERS = mapOf(
        "com.whatsapp" to setOf("whatsapp"),
        "com.whatsapp.w4b" to setOf("whatsapp business"),
        "com.viber.voip" to setOf("viber"),
        "org.telegram.messenger" to setOf("telegram"),
        "org.telegram.messenger.web" to setOf("telegram"),
        "org.thoughtcrime.securesms" to setOf("signal"),
        "com.facebook.orca" to setOf("messenger"),
        "jp.naver.line.android" to setOf("line"),
    )

    /** Folders that hold the user's own uploads or other people's statuses, never a received message. */
    private val EXCLUDED_FOLDERS = setOf("sent", ".statuses", "statuses")

    fun candidates(
        files: List<PhoneMediaFile>,
        packageName: String,
        appLabel: String,
        messageTimestamp: Long,
        messageText: String,
        anyApp: Boolean,
    ): List<PhoneMediaFile> {
        val expected = expectedKind(messageText)
        return files.asSequence()
            .filter { it.addedAt in (messageTimestamp - BEFORE_MS)..(messageTimestamp + AFTER_MS) }
            .filter { !isExcluded(it.path) }
            .filter { anyApp || isFromApp(it, packageName, appLabel) }
            .sortedWith(compareBy<PhoneMediaFile>({ if (expected == null || it.kind == expected) 0 else 1 }, { abs(it.addedAt - messageTimestamp) }))
            .take(MAX_RESULTS)
            .toList()
    }

    fun isFromApp(file: PhoneMediaFile, packageName: String, appLabel: String): Boolean {
        val segments = segments(file.path)
        val names = buildSet {
            add(packageName.lowercase())
            KNOWN_FOLDERS[packageName]?.let { addAll(it) }
            appLabel.trim().lowercase().takeIf { it.length >= MIN_LABEL }?.let { add(it) }
        }
        // Another chat app's folder (com.whatsapp.w4b or "WhatsApp Business" when looking for com.whatsapp, ...) is
        // never ours, whoever owns the file. Names this app shares (both Telegram builds use "Telegram") still count.
        val otherPackages = KNOWN_FOLDERS.keys - packageName
        val otherFolders = otherPackages.flatMap { KNOWN_FOLDERS.getValue(it) }.toSet() - names
        if (segments.any { it in otherPackages || it in otherFolders }) return false
        if (file.ownerPackage != null) {
            if (file.ownerPackage == packageName) return true
            if (file.ownerPackage in otherPackages) return false
        }
        // Whole folder names only: "WhatsApp" matches WhatsApp's root folder but not "WhatsApp Business".
        return segments.any { it in names }
    }

    fun isExcluded(path: String): Boolean = segments(path).any { it in EXCLUDED_FOLDERS }

    /** The kind a placeholder text suggests ("📷 Photo", "🎥 Video", "🎤 Voice message"), or null. */
    fun expectedKind(text: String): PhoneMediaKind? {
        val t = text.lowercase()
        return when {
            VIDEO_HINTS.any { it in t } -> PhoneMediaKind.VIDEO
            AUDIO_HINTS.any { it in t } -> PhoneMediaKind.AUDIO
            IMAGE_HINTS.any { it in t } -> PhoneMediaKind.IMAGE
            else -> null
        }
    }

    private fun segments(path: String): List<String> =
        path.split('/', '\\').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private const val MIN_LABEL = 3
    private val VIDEO_HINTS = listOf("🎥", "📹", "video", "vidéo", "clip")
    private val AUDIO_HINTS = listOf("🎤", "🎵", "🎙", "voice", "audio", "glas", "sprach")
    private val IMAGE_HINTS = listOf("📷", "📸", "🖼", "photo", "foto", "image", "picture", "slika", "bild", "sticker", "stiker", "gif")
}
