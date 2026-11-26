package com.riyadm.unsendvault.data

import android.content.Context
import android.util.Log
import com.riyadm.unsendvault.data.backup.BackupManagerImpl
import com.riyadm.unsendvault.data.backup.BackupStore
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.db.verifyOrInitKeyCheck
import com.riyadm.unsendvault.data.media.MediaVault
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.retention.RetentionRunner
import com.riyadm.unsendvault.data.retention.WorkManagerRetentionScheduler
import com.riyadm.unsendvault.data.security.AndroidKeystoreKeyWrapper
import com.riyadm.unsendvault.data.security.KeyWrapper
import com.riyadm.unsendvault.data.security.FieldCipher
import com.riyadm.unsendvault.data.security.LegacyArchive
import com.riyadm.unsendvault.data.security.LegacyArchiveNotice
import com.riyadm.unsendvault.data.security.VaultDatabaseHolder
import com.riyadm.unsendvault.data.security.VaultKeyException
import com.riyadm.unsendvault.data.security.VaultKeyManager
import com.riyadm.unsendvault.data.security.VaultKeyState
import com.riyadm.unsendvault.data.settings.DataStoreSettingsRepository
import com.riyadm.unsendvault.data.settings.vaultSettingsDataStore
import com.riyadm.unsendvault.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production wiring of the data layer:
 *  - VaultRepository: Room on the framework SQLite with field-level encryption ([FieldCipher]); root secret from
 *    [VaultKeyManager] (Keystore-wrapped, noBackupFilesDir). Opened lazily on Dispatchers.IO at the first query/write.
 *    Health/recovery and the 1.0.15 legacy-archive notice: `container.vaultRepository.vaultHealth`.
 *  - SettingsRepository: Preferences DataStore "settings".
 *  - BackupManager: password-encrypted SAF export/import.
 *  - RetentionScheduler: daily WorkManager job; self-syncs with settings.retentionDays once created.
 */
object DataModule {
    const val KEY_FILE_NAME = "vault.key"
    const val LEGACY_NOTICE_FILE_NAME = "legacy-archive-discarded"

    fun createVaultRepository(context: Context): VaultRepository {
        val app = context.applicationContext
        // Pictures of media messages (opt-in), encrypted with the vault key; wiped together with the vault.
        val media = MediaVault(File(app.noBackupFilesDir, MediaVault.DIR_NAME))
        return RoomVaultRepository(encryptedHolder(app, mediaVault = media), media)
    }

    fun createSettingsRepository(context: Context, container: AppContainer): SettingsRepository =
        DataStoreSettingsRepository(context.applicationContext.vaultSettingsDataStore, container.appScope)

    fun createBackupManager(context: Context, container: AppContainer): BackupManager {
        val store = container.vaultRepository as? BackupStore
            ?: return object : BackupManager {
                override suspend fun export(destination: android.net.Uri, password: CharArray) =
                    BackupResult.Failure(BackupResult.Reason.IO_ERROR, "Backup requires the Room vault")
                override suspend fun import(source: android.net.Uri, password: CharArray, mode: ImportMode) =
                    BackupResult.Failure(BackupResult.Reason.IO_ERROR, "Backup requires the Room vault")
            }
        return BackupManagerImpl(context.applicationContext.contentResolver, store, container.settingsRepository)
    }

    fun createRetentionScheduler(context: Context, container: AppContainer): RetentionScheduler {
        val scheduler = WorkManagerRetentionScheduler(
            context.applicationContext,
            RetentionRunner(container.settingsRepository, container.vaultRepository),
        )
        scheduler.startSync(container.settingsRepository, container.appScope)
        return scheduler
    }

    /**
     * Builds the production vault holder. Database, key file and notice marker live in credential-encrypted app storage
     * (database in databases/, key and marker in noBackupFilesDir). Opening, on Dispatchers.IO at the first query/write:
     *  1. [LegacyArchive.resolve]: a 1.0.15 SQLCipher archive is removed (raising the one-time notice);
     *  2. the root secret is unwrapped by the Keystore once, the field and index keys are derived ([FieldCipher]) and
     *     the root secret is zeroed;
     *  3. Room opens/migrates the database and the key check in vault_meta is verified (or written on a new vault).
     * Parameters exist so instrumented tests can use their own files/alias and never touch the real vault.
     */
    internal fun encryptedHolder(
        app: Context,
        dbName: String = VaultDatabase.FILE_NAME,
        keyFile: File = File(app.noBackupFilesDir, KEY_FILE_NAME),
        wrapper: KeyWrapper = AndroidKeystoreKeyWrapper(),
        legacyDbName: String? = VaultDatabase.LEGACY_FILE_NAME,
        noticeFile: File? = File(app.noBackupFilesDir, LEGACY_NOTICE_FILE_NAME),
        mediaVault: MediaVault? = null,
    ): VaultDatabaseHolder {
        val keyManager = VaultKeyManager(keyFile, wrapper)
        val notice = LegacyArchiveNotice(noticeFile)
        return VaultDatabaseHolder(
            opener = {
                withContext(Dispatchers.IO) {
                    val dbFile = app.getDatabasePath(dbName)
                    notice.refresh()
                    if (legacyDbName != null) {
                        val outcome = try {
                            LegacyArchive.resolve(app.getDatabasePath(legacyDbName), dbFile)
                        } catch (e: IOException) {
                            throw VaultKeyException(VaultKeyState.Reason.DATABASE_UNREADABLE, "Cannot resolve the 1.0.15 archive", e)
                        }
                        if (outcome == LegacyArchive.Outcome.DISCARDED) {
                            Log.w(TAG, "Removed an archive from 1.0.15 (SQLCipher format), which this version cannot read")
                            notice.raise()
                        }
                    }
                    val root = keyManager.loadOrCreateRootSecret(databaseExists = dbFile.exists())
                    val cipher = try {
                        FieldCipher.fromRootSecret(root, onDecryptFailure = ::logDecryptFailure)
                    } finally {
                        root.fill(0)
                    }
                    val db = VaultDatabase.build(app, dbName, cipher)
                    try {
                        // Force the open (and any migration) now so problems surface as a state, not as a query crash.
                        db.openHelper.writableDatabase
                        if (!db.verifyOrInitKeyCheck()) {
                            throw VaultKeyException(VaultKeyState.Reason.KEY_LOST, "The archive was encrypted with a different key")
                        }
                    } catch (e: VaultKeyException) {
                        runCatching { db.close() }
                        throw e
                    } catch (e: Exception) {
                        runCatching { db.close() }
                        throw VaultKeyException(VaultKeyState.Reason.DATABASE_UNREADABLE, "Database cannot be opened", e)
                    }
                    db
                }
            },
            destroyer = {
                withContext(Dispatchers.IO) {
                    // The database is already closed by the holder.
                    app.deleteDatabase(dbName)
                    legacyDbName?.let { LegacyArchive.deleteAll(app.getDatabasePath(it)) }
                    keyManager.destroy()
                    // Pictures are encrypted with the destroyed key and their ids restart with a new database.
                    mediaVault?.clear()
                }
            },
            legacyNotice = notice,
        )
    }

    private val decryptFailuresLogged = AtomicInteger()

    private fun logDecryptFailure(field: FieldCipher.Field, e: Throwable) {
        // Never logs content; capped so a damaged archive can't flood logcat.
        if (decryptFailuresLogged.incrementAndGet() <= 20) Log.w(TAG, "Cannot decrypt ${field.label}: ${e.javaClass.simpleName}")
    }

    private const val TAG = "UvVault"
}
