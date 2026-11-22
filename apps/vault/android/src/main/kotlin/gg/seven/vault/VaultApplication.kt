package gg.seven.vault

import android.app.Application
import java.io.File

/**
 * Process-wide storage: the vault directories, the temporary plaintext area and the
 * automatic-backup queue, so WorkManager can deliver prepared backups without the UI.
 */
class VaultApplication : Application() {
    val repository by lazy {
        VaultRepository(File(noBackupFilesDir, "Vault"), File(cacheDir, "Vault-plaintext"))
    }
    val automatic by lazy {
        AutomaticBackup(File(noBackupFilesDir, "Vault-backup-queue"), DocumentTreeWriter(this))
    }
}
