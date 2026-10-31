package com.riyadm.unsendvault.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.data.db.Migrations
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.db.verifyOrInitKeyCheck
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.security.FieldCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM run of the migrations. Robolectric can't expose test assets to MigrationTestHelper, so old databases are
 * created from the exported app/schemas/…/N.json (tables, indices, FTS triggers, Room identity hash), filled with
 * [MigrationFixtures], then opened by Room at v3; Room validates the migrated schema against v3 on open (throws
 * "Migration didn't properly handle …" on any mismatch). The instrumented twin uses MigrationTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val dbName = "migration-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = FieldCipher.random()

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    private fun schemaFile(version: Int): File {
        val rel = "schemas/com.riyadm.unsendvault.data.db.VaultDatabase/$version.json"
        return listOf(File(rel), File("app/$rel")).first { it.exists() }
    }

    private fun helper(version: Int, onUpgrade: (SupportSQLiteDatabase) -> Unit = { error("no upgrade") }) =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("must exist")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = onUpgrade(db)
                }).build(),
        )

    /** Creates the database exactly as Room would have at [version], then inserts fixtures with [fill]. */
    private fun createAt(version: Int, fill: (SupportSQLiteDatabase) -> Unit) {
        val db = Json.parseToJsonElement(schemaFile(version).readText()).jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            for (entity in db.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                sql.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { idx ->
                    sql.execSQL(idx.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
                e["contentSyncTriggers"]?.jsonArray?.forEach { sql.execSQL(it.jsonPrimitive.content) }
            }
            db.getValue("setupQueries").jsonArray.forEach { sql.execSQL(it.jsonPrimitive.content) }
            sql.version = version
        }
        helper(version).also { fill(it.writableDatabase) }.close()
    }

    @Test
    fun migrate1To2_alone_addsColumnsAndFts() {
        createAt(1, MigrationFixtures::insertV1)
        val h = helper(2) { Migrations.MIGRATION_1_2.migrate(it) }
        try {
            MigrationFixtures.assertV2(h.writableDatabase)
        } finally {
            h.close()
        }
    }

    @Test
    fun migrate1To2To3_encryptsEverything_validatesSchema_keepsSearchAndIdentity() = runBlocking {
        createAt(1, MigrationFixtures::insertV1)
        val room = VaultDatabase.build(context, dbName, cipher, allowMainThreadQueries = true)
        try {
            // Opening runs MIGRATION_1_2 then Migration2To3 + Room's schema validation.
            val raw = room.openHelper.writableDatabase
            assertEquals(3, raw.version)
            MigrationFixtures.assertV3(raw, cipher, fromV2 = false)
            assertTrue(room.verifyOrInitKeyCheck())
            val repo = RoomVaultRepository(room)

            val hits = repo.observeMessages(MessageQuery(text = "migrated")).first()
            assertEquals(listOf("Hello migrated world"), hits.map { it.text })
            assertEquals("Bob", hits.single().conversationTitle)
            assertNull(hits.single().lastRemovalReason)
            val detail = repo.observeMessage(hits.single().id).first()!!
            assertEquals(2, detail.message.revisionCount)
            assertEquals(listOf("Hello world"), detail.revisions.map { it.text })
            // Sender "Bob" (message 10) and conversation title "Bob" (message 11, no sender) both match.
            assertEquals(listOf("Bob", null), repo.observeMessages(MessageQuery(text = "bob")).first().map { it.sender })
            assertEquals("c1", repo.observeConversations().first().single().conversationKey)
            assertEquals("Hello migrated world", repo.observeConversations().first().single().lastMessagePreview)

            // Identity still works against migrated rows: the same message re-posted is UNCHANGED (same conversation,
            // same row), a new text in the same precise slot is an edit.
            val same = Fx.msg("Hello migrated world", sender = "Bob", ts = 2000, key = "c1", title = "Bob")
            assertEquals(IngestResult(0, 0, 1, 0), repo.ingest(Fx.parsed(same), Fx.T0).counts())
            assertEquals(IngestResult(0, 0, 1, 0), repo.ingest(Fx.parsed(Fx.msg("second", sender = null, ts = 1000 + Fx.MIN, precise = false, title = "Bob")), Fx.T0).counts())
            assertEquals(IngestResult(0, 1, 0, 0), repo.ingest(Fx.parsed(same.copy(text = "Hello migrated world, edited")), Fx.T0).counts())
            assertEquals(2, repo.observeStats().first().totalMessages)
            assertEquals(1, repo.observeStats().first().totalConversations)
            // New writes after migration work end to end.
            repo.ingest(Fx.parsed(Fx.msg("post migration words", key = "c1", sender = "Bob")), Fx.T0)
            assertEquals(1, repo.observeMessages(MessageQuery(text = "post migration")).first().size)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate2To3_keepsRemovalDataAndNotificationLookup() = runBlocking {
        createAt(2, MigrationFixtures::insertV2)
        val room = VaultDatabase.build(context, dbName, cipher, allowMainThreadQueries = true)
        try {
            val raw = room.openHelper.writableDatabase
            assertEquals(3, raw.version)
            MigrationFixtures.assertV3(raw, cipher, fromV2 = true)
            val repo = RoomVaultRepository(room)
            val migrated = repo.observeMessages(MessageQuery(text = "hello")).first().single()
            assertEquals(RemovalReason.USER_DISMISSED, migrated.lastRemovalReason)
            // The notification key lookup (now a blind index) still finds the migrated row.
            repo.recordRemoval(RemovalEvent(Fx.PKG, MigrationFixtures.NOTIFICATION_KEY, RemovalReason.APP_CANCELLED, 9_999))
            val after = repo.observeMessage(migrated.id).first()!!.message
            assertEquals(RemovalReason.APP_CANCELLED, after.lastRemovalReason)
            assertEquals(9_999L, after.lastRemovedAt)
            // Deletion evidence decrypts.
            assertEquals("deleted", repo.observeMessages(MessageQuery(possiblyDeletedOnly = true)).first().single().deletionEvidence?.markerText)
        } finally {
            room.close()
        }
    }

    @Test
    fun migratedArchive_withWrongKey_failsKeyCheck() = runBlocking {
        createAt(2, MigrationFixtures::insertV2)
        VaultDatabase.build(context, dbName, cipher, allowMainThreadQueries = true).apply { openHelper.writableDatabase }.close()
        val other = VaultDatabase.build(context, dbName, FieldCipher.random(), allowMainThreadQueries = true)
        try {
            assertEquals(false, other.verifyOrInitKeyCheck())
        } finally {
            other.close()
        }
    }
}
