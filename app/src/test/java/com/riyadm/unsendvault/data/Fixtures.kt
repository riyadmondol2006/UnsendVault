package com.riyadm.unsendvault.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.DeletionMarker
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.security.FieldCipher
import org.junit.After
import org.junit.Before

object Fx {
    const val PKG = "com.example.chat"
    const val LABEL = "Chat"
    const val T0 = 1_700_000_000_000L
    const val MIN = 60_000L

    fun msg(
        text: String,
        sender: String? = "Alice",
        ts: Long = T0,
        precise: Boolean = true,
        key: String = "c1",
        title: String = "Alice",
        kind: ContentKind = ContentKind.TEXT,
        isGroup: Boolean = false,
        pkg: String = PKG,
    ) = CapturedMessage(pkg, key, title, isGroup, sender, text, kind, ts, precise)

    fun parsed(
        vararg messages: CapturedMessage,
        markers: List<DeletionMarker> = emptyList(),
        notificationKey: String = "0|$PKG|1|null|10001",
        pkg: String = PKG,
        label: String = LABEL,
    ) = ParsedNotification(pkg, label, notificationKey, T0, "test", messages.toList(), markers)

    fun marker(sender: String?, ts: Long, text: String = "This message was deleted", key: String = "c1", pkg: String = PKG) =
        DeletionMarker(pkg, key, sender, text, "testadapter", ts)
}

/** The four counters only (drops the per-row details used by saved-message notifications). */
fun com.riyadm.unsendvault.capture.IngestResult.counts() =
    com.riyadm.unsendvault.capture.IngestResult(inserted, updated, unchanged, markedPossiblyDeleted)

/** In-memory Room database on the framework (Robolectric) SQLite, with a random field-encryption key. */
abstract class RoomTestBase {
    protected lateinit var context: Context
    protected lateinit var db: VaultDatabase
    protected lateinit var repo: RoomVaultRepository

    @Before
    fun openDb() {
        context = ApplicationProvider.getApplicationContext()
        db = VaultDatabase.build(context, name = null, cipher = FieldCipher.random(), allowMainThreadQueries = true)
        repo = RoomVaultRepository(db)
    }

    @After
    fun closeDb() {
        db.close()
    }
}
