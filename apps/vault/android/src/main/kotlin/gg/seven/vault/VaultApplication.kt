package gg.seven.vault

import android.app.Application
import gg.seven.vault.core.VaultException
import gg.seven.vault.core.vaultErrorDescription
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

/** The sentence to show: the core's for Vault's own errors (iOS `vaultMessage`). */
val Throwable.vaultMessage: String
    get() =
        when (this) {
            is gg.seven.vault.core.RecognitionFailure ->
                gg.seven.vault.core.recognitionFailureDescription(this)
            is VaultException -> vaultErrorDescription(this)
            is java.io.FileNotFoundException ->
                "The file could not be opened. It may have moved or access may have changed."
            else ->
                message?.takeIf { it.isNotBlank() }
                    ?: "Something went wrong. Existing data has been preserved."
        }
