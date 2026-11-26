package com.riyadm.unsendvault.data.security

import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Field-level encryption for the archive database (pure JVM, no Android dependency).
 *
 * Keys: two 256-bit subkeys derived with HKDF-SHA256 from the vault's 32-byte root secret (see [fromRootSecret]); the
 * root secret itself is stored only wrapped by an Android Keystore key ([VaultKeyManager]). Both subkeys live in memory
 * as [SecretKeySpec]s for the life of the process, so encryption and decryption are plain software AES-GCM/HMAC (a few
 * microseconds per field) and never an IPC to the Keystore.
 *
 * Encrypted value (stored as a BLOB): `[VERSION:u8][nonce:12][ciphertext][tag:16]`, AES-256-GCM with a random nonce
 * per write. The additional authenticated data binds the version, the column ([Field.label]) and the row's stable owner
 * ([forMessage], [forRevision], [forConversation]), so a ciphertext copied into another column or another
 * conversation fails authentication instead of showing up as someone else's text.
 *
 * Blind indexes ([blindIndex]): HMAC-SHA256 under the separate index key over `domain || 0x00 || UTF-8(value)`,
 * truncated to 16 bytes and base64url-encoded (22 chars). They support equality lookups only (message identity,
 * conversation lookup) and are domain-separated, so the same string in two columns gives unrelated indexes. Like any
 * deterministic index they reveal which rows hold equal values (e.g. how many messages share one sender).
 *
 * Threat model: protects the content columns against someone who obtains a copy of the database file without being
 * able to run code as this app with the device unlocked (forensic or root file copies, a leaked file). It does not
 * protect against code running as the app, or a rooted attacker who can ask the Keystore to unwrap the root secret
 * while the device is unlocked, or who reads this process's memory.
 */
class FieldCipher(
    private val fieldKey: SecretKey,
    private val indexKey: SecretKey,
    private val random: SecureRandom = SecureRandom(),
    private val onDecryptFailure: (Field, Throwable) -> Unit = { _, _ -> },
) {
    /** Encrypted columns. The label is part of the AAD and must never change for a stored column. */
    enum class Field(val label: String) {
        MESSAGE_TEXT("messages.text"),
        MESSAGE_SENDER("messages.sender"),
        MESSAGE_MARKER("messages.deletion_marker_text"),
        REVISION_TEXT("message_revisions.text"),
        CONVERSATION_TITLE("conversations.title"),
        CONVERSATION_PREVIEW("conversations.last_message_preview"),
        CONVERSATION_KEY("conversations.conversation_key"),
        KEY_CHECK("vault_meta.key_check"),

        /** Picture files of media messages (see com.riyadm.unsendvault.data.media.MediaVault); not a database column. */
        MEDIA_CONTENT("media.content"),

        /** A linked phone file (MediaStore URI + MIME type), see MediaVault.writeLink. */
        MEDIA_LINK("media.link"),
    }

    /** Blind-index domains. The label is hashed into every index value and must never change. */
    enum class IndexDomain(val label: String) {
        TEXT("messages.text_index"),
        SENDER("messages.sender_index"),
        CONVERSATION("conversations.conversation_index"),
        NOTIFICATION("messages.last_notification_index"),
    }

    private val ciphers = ThreadLocal.withInitial { Cipher.getInstance(TRANSFORMATION) }
    private val macs = ThreadLocal.withInitial { Mac.getInstance(HMAC).apply { init(indexKey) } }

    fun encrypt(plain: String, field: Field, binding: String): ByteArray {
        val input = plain.toByteArray(UTF_8)
        try {
            return encryptBytes(input, field, binding)
        } finally {
            input.fill(0)
        }
    }

    /** Same format and AAD as [encrypt], for binary content. Does not modify or zero [input]. */
    fun encryptBytes(input: ByteArray, field: Field, binding: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = ciphers.get()!!
        cipher.init(Cipher.ENCRYPT_MODE, fieldKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(field, binding))
        val out = ByteArray(1 + NONCE_BYTES + cipher.getOutputSize(input.size))
        out[0] = VERSION
        System.arraycopy(nonce, 0, out, 1, NONCE_BYTES)
        val written = cipher.doFinal(input, 0, input.size, out, 1 + NONCE_BYTES)
        return if (written == out.size - 1 - NONCE_BYTES) out else out.copyOf(1 + NONCE_BYTES + written)
    }

    fun encryptOrNull(plain: String?, field: Field, binding: String): ByteArray? = plain?.let { encrypt(it, field, binding) }

    /** Plaintext, or null when [blob] is malformed, was written for another column/owner, or fails authentication. */
    fun decryptOrNull(blob: ByteArray, field: Field, binding: String): String? {
        val plain = decryptBytesOrNull(blob, field, binding) ?: return null
        return try {
            String(plain, UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    /** Binary counterpart of [decryptOrNull]; the caller owns the returned array. */
    fun decryptBytesOrNull(blob: ByteArray, field: Field, binding: String): ByteArray? {
        if (blob.size < MIN_BLOB || blob[0] != VERSION) {
            onDecryptFailure(field, GeneralSecurityException("malformed blob (${blob.size} bytes)"))
            return null
        }
        return try {
            val cipher = ciphers.get()!!
            cipher.init(Cipher.DECRYPT_MODE, fieldKey, GCMParameterSpec(TAG_BITS, blob, 1, NONCE_BYTES))
            cipher.updateAAD(aad(field, binding))
            cipher.doFinal(blob, 1 + NONCE_BYTES, blob.size - 1 - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            onDecryptFailure(field, e)
            null
        } catch (e: IllegalArgumentException) {
            onDecryptFailure(field, e)
            null
        } catch (e: IllegalStateException) {
            onDecryptFailure(field, e)
            null
        }
    }

    /** Plaintext, or [UNREADABLE] for a damaged value, so one corrupt row never breaks a list. */
    fun decrypt(blob: ByteArray, field: Field, binding: String): String = decryptOrNull(blob, field, binding) ?: UNREADABLE

    fun decryptNullable(blob: ByteArray?, field: Field, binding: String): String? = blob?.let { decrypt(it, field, binding) }

    /** Deterministic keyed index of [value] for equality lookups. */
    fun blindIndex(value: String, domain: IndexDomain): String {
        val mac = macs.get()!!
        mac.update(domain.label.toByteArray(UTF_8))
        mac.update(0)
        val input = value.toByteArray(UTF_8)
        mac.update(input)
        input.fill(0)
        val full = mac.doFinal()
        val truncated = full.copyOf(INDEX_BYTES)
        full.fill(0)
        return INDEX_ENCODER.encodeToString(truncated)
    }

    fun keyCheckBlob(): ByteArray = encrypt(KEY_CHECK_PLAINTEXT, Field.KEY_CHECK, KEY_CHECK_BINDING)

    fun verifyKeyCheck(blob: ByteArray): Boolean =
        decryptOrNull(blob, Field.KEY_CHECK, KEY_CHECK_BINDING) == KEY_CHECK_PLAINTEXT

    private fun aad(field: Field, binding: String): ByteArray =
        "$AAD_PREFIX\u0000${field.label}\u0000$binding".toByteArray(UTF_8)

    companion object {
        const val VERSION: Byte = 1
        const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val MIN_BLOB = 1 + NONCE_BYTES + TAG_BITS / 8
        const val INDEX_BYTES = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC = "HmacSHA256"
        private const val AAD_PREFIX = "uv-field-v1"
        private const val KEY_CHECK_PLAINTEXT = "unsendvault-key-check-v1"
        private const val KEY_CHECK_BINDING = "vault"
        private val INDEX_ENCODER = Base64.getUrlEncoder().withoutPadding()

        /** HKDF info strings. Changing them changes every derived key, i.e. makes existing archives unreadable. */
        const val INFO_FIELD_KEY = "uv-field-enc-v1"
        const val INFO_INDEX_KEY = "uv-blind-index-v1"

        /** Shown instead of a value that cannot be decrypted (damaged row). */
        const val UNREADABLE = "[unreadable entry]"

        /** AAD owner of messages.text / sender / deletion_marker_text: the row's conversation. */
        fun forMessage(conversationId: Long): String = "c:$conversationId"

        /** AAD owner of message_revisions.text: the message the revision belongs to. */
        fun forRevision(messageId: Long): String = "m:$messageId"

        /** AAD owner of a media file: the message it belongs to (message ids are never reused, see MediaVault). */
        fun forMedia(messageId: Long): String = "media:$messageId"

        /** AAD owner of conversations.title / last_message_preview / conversation_key: the conversation's identity. */
        fun forConversation(packageName: String, conversationIndex: String): String = "p:$packageName|$conversationIndex"

        /**
         * Derives the field key and the blind-index key from [rootSecret] (32 random bytes) with HKDF-SHA256 (zero salt,
         * distinct info strings). The derived byte arrays are zeroed once wrapped in [SecretKeySpec] (which keeps its
         * own copy for the life of the process); the caller still owns and should zero [rootSecret].
         */
        fun fromRootSecret(
            rootSecret: ByteArray,
            random: SecureRandom = SecureRandom(),
            onDecryptFailure: (Field, Throwable) -> Unit = { _, _ -> },
        ): FieldCipher {
            require(rootSecret.size == VaultKeyManager.RAW_KEY_BYTES) { "root secret must be 32 bytes" }
            val prk = Hkdf.extract(null, rootSecret)
            val fieldBytes = Hkdf.expand(prk, INFO_FIELD_KEY.toByteArray(UTF_8), 32)
            val indexBytes = Hkdf.expand(prk, INFO_INDEX_KEY.toByteArray(UTF_8), 32)
            prk.fill(0)
            try {
                return FieldCipher(SecretKeySpec(fieldBytes, "AES"), SecretKeySpec(indexBytes, HMAC), random, onDecryptFailure)
            } finally {
                fieldBytes.fill(0)
                indexBytes.fill(0)
            }
        }

        /** A cipher with a fresh random root secret (tests, throwaway vaults). */
        fun random(): FieldCipher {
            val root = ByteArray(VaultKeyManager.RAW_KEY_BYTES).also(SecureRandom()::nextBytes)
            try {
                return fromRootSecret(root)
            } finally {
                root.fill(0)
            }
        }
    }
}
