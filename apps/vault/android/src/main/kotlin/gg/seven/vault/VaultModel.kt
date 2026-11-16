package gg.seven.vault

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import gg.seven.vault.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the root shows over the library: a preview, or prepared files to save or share. */
sealed interface Presentation {
    data class Preview(val item: VaultItem, val file: File) : Presentation

    data class Files(val files: List<File>, val backup: Boolean = false) : Presentation
}

/** iOS `VaultModel`: the session as the UI sees it, the lock lifecycle and automatic backup. */
class VaultModel(app: Application) : AndroidViewModel(app) {
    private val application = app as VaultApplication
    val repository = application.repository
    val automatic = application.automatic
    val store =
        SevenStore(
            app,
            BuildConfig.PERSONAL_UNLOCK,
            sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY),
            viewModelScope,
        )

    var library by mutableStateOf<VaultLibrary?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
    var recoveryKey by mutableStateOf<String?>(null)
    var presentation by mutableStateOf<Presentation?>(null)
        private set
    var generation by mutableIntStateOf(0)
        private set
    var importResult by mutableStateOf("")
        private set
    var notice by mutableStateOf<String?>(null)
    var hasBiometricKey by mutableStateOf(BiometricKey.storedId(app) != null)
        private set

    var automaticStatus by mutableStateOf<BackupStatus?>(null)
        private set
    var automaticMessage by mutableStateOf<String?>(null)
        private set
    var automaticWorking by mutableStateOf(false)
        private set
    var automaticNeedsPreparation by mutableStateOf(false)
        private set

    /** A system picker, camera, share sheet or prompt of ours is in front: don't lock for it. */
    var external = false

    private var started = false
    private var operation = 0
    private var automaticTask: Job? = null
    private var deliveryTask: Job? = null

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                repository.prepare()
            } catch (e: Exception) {
                error = e.vaultMessage
            }
            try {
                automatic.prepare()
            } catch (e: Exception) {
                automaticMessage = e.vaultMessage
            }
            retryAutomaticBackups()
        }
    }

    fun lock() {
        automaticTask?.cancel()
        automaticTask = null
        automaticStatus = null
        automaticMessage = null
        generation += 1
        operation += 1
        library = null
        recoveryKey = null
        presentation = null
        error = null
        importResult = ""
        // Stay busy until the repository has discarded keys and any in-flight operation finished.
        busy = true
        progress = "Locking…"
        val token = operation
        viewModelScope.launch {
            repository.lock()
            if (token == operation) {
                busy = false
                progress = ""
            }
            retryAutomaticBackups()
        }
    }

    private fun perform(label: String, action: suspend () -> Pair<VaultLibrary, String?>) {
        if (busy) return
        busy = true
        progress = label
        operation += 1
        val token = operation
        viewModelScope.launch {
            try {
                val (value, recovery) = action()
                if (token != operation) return@launch
                library = value
                recoveryKey = recovery
                scheduleAutomaticBackup()
            } catch (e: BiometricKey.Cancelled) {
                // The person chose their secret instead.
            } catch (e: Exception) {
                if (token == operation) error = e.vaultMessage
            } finally {
                if (token == operation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    /** A change to the open vault; the UI follows the committed library only if still unlocked. */
    fun update(label: String = "Saving…", then: () -> Unit = {}, action: suspend () -> Unit) {
        if (busy || library == null) return
        busy = true
        progress = label
        val token = operation
        viewModelScope.launch {
            try {
                action()
                val value = repository.snapshot()
                if (token == operation) {
                    library = value
                    scheduleAutomaticBackup()
                    then()
                }
            } catch (e: BiometricKey.Cancelled) {
            } catch (e: Exception) {
                if (token == operation) error = e.vaultMessage
            } finally {
                if (token == operation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    fun create(name: String, secret: String, kind: VaultCredentialKind) {
        if (!store.paidFeaturesAvailable) {
            error = "Start the trial or unlock to add a vault."
            return
        }
        perform("Creating encrypted vault…") {
            val result = repository.create(name, secret, kind)
            result.library to result.recoveryKey
        }
    }

    fun unlock(credential: String, recovery: Boolean, kind: VaultCredentialKind) =
        perform("Opening vault…") { repository.unlock(credential, recovery, kind) to null }

    private fun displayName(uri: Uri): String =
        runCatching {
                application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "Imported file")

    /** Files (Storage Access Framework) or the Photo Picker: each read once, straight from its descriptor. */
    fun importDocuments(uris: List<Uri>, folder: UUID?, fromPhotos: Boolean) {
        if (busy || library == null || !store.paidFeaturesAvailable || uris.isEmpty()) return
        busy = true
        val token = operation
        viewModelScope.launch {
            var successes = 0
            val failures = mutableListOf<String>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    if (token != operation) return@launch
                    progress = if (fromPhotos) "Loading and encrypting ${index + 1} of ${uris.size}…" else "Encrypting ${index + 1} of ${uris.size}…"
                    val name = displayName(uri)
                    try {
                        val type = Types.identifier(application.contentResolver.getType(uri), name)
                        val descriptor = application.contentResolver.openFileDescriptor(uri, "r") ?: throw VaultException.MissingFile()
                        descriptor.use { repository.importDescriptor(it.fd, name, type, folder) }
                        successes += 1
                    } catch (e: Exception) {
                        failures += if (fromPhotos) "Item ${index + 1}: ${e.vaultMessage}" else "$name: ${e.vaultMessage}"
                    }
                }
                if (token != operation) return@launch
                library = runCatching { repository.snapshot() }.getOrNull()
                scheduleAutomaticBackup()
                importResult = if (fromPhotos) "$successes imported. Originals stay in your gallery." else "$successes imported. Originals stay where they were."
                if (failures.isNotEmpty())
                    error = (if (fromPhotos) "${failures.size} imports failed:\n" else "${failures.size} could not be imported:\n") + failures.joinToString("\n")
            } finally {
                if (token == operation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    /** A camera capture: written only to Vault's temporary area, imported, then removed. */
    fun importCapture(file: File, folder: UUID?) {
        if (busy || library == null || !store.paidFeaturesAvailable) {
            file.delete()
            return
        }
        busy = true
        progress = "Encrypting 1 of 1…"
        val token = operation
        viewModelScope.launch {
            try {
                repository.importFile(file.path, file.name, "public.jpeg", folder)
                if (token == operation) {
                    library = repository.snapshot()
                    scheduleAutomaticBackup()
                    importResult = "1 imported. Nothing was added to your gallery."
                }
            } catch (e: Exception) {
                if (token == operation) error = e.vaultMessage
            } finally {
                file.delete()
                if (token == operation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    fun preview(item: VaultItem) {
        if (busy) return
        val token = operation
        update("Preparing preview…") {
            val file = repository.export(item.id)
            if (token == operation) presentation = Presentation.Preview(item, file)
        }
    }

    fun export(ids: Set<UUID>) {
        if (ids.isEmpty()) return
        val token = operation
        update("Preparing originals…") {
            val files = mutableListOf<File>()
            val failures = mutableListOf<String>()
            for (id in ids.sortedBy { it.upper() }) {
                if (token != operation) return@update
                try {
                    files += repository.export(id)
                } catch (e: Exception) {
                    failures += e.vaultMessage
                }
            }
            if (token != operation) return@update
            if (files.isNotEmpty()) presentation = Presentation.Files(files)
            if (failures.isNotEmpty()) error = "${failures.size} exports failed. Successful originals are ready.\n" + failures.joinToString("\n")
        }
    }

    fun backup() {
        val token = operation
        update("Verifying and preparing encrypted backup…") {
            val file = repository.backup()
            if (token == operation) presentation = Presentation.Files(listOf(file), backup = true)
        }
    }

    fun restore(uri: Uri, credential: String, recovery: Boolean, kind: VaultCredentialKind) =
        perform("Verifying backup…") {
            val descriptor = application.contentResolver.openFileDescriptor(uri, "r") ?: throw VaultException.MissingFile()
            descriptor.use { repository.restoreDescriptor(it.fd, credential, recovery, kind) } to null
        }

    fun cleanPresentation() {
        presentation = null
        viewModelScope.launch { runCatching { repository.clearTemporary() } }
    }

    fun showNotice(text: String) {
        notice = text
    }

    fun enableBiometrics(activity: FragmentActivity) {
        external = true
        update(then = { showNotice("Biometric unlock is on for this vault.") }) {
            try {
                val (id, secret) = repository.custodyMaterial()
                BiometricKey.enable(activity, id, secret)
                hasBiometricKey = true
            } finally {
                external = false
            }
        }
    }

    fun disableBiometrics() {
        BiometricKey.remove(application)
        hasBiometricKey = false
        showNotice("Biometric unlock is off.")
    }

    fun unlockBiometrics(activity: FragmentActivity) {
        if (BiometricKey.storedId(application) == null) return
        external = true
        perform("Authenticating…") {
            try {
                val (id, secret) =
                    try {
                        BiometricKey.read(activity)
                    } catch (e: BiometricKey.Unavailable) {
                        hasBiometricKey = BiometricKey.storedId(application) != null
                        throw e
                    }
                try {
                    repository.unlock(id, secret) to null
                } finally {
                    secret.fill(0)
                }
            } finally {
                external = false
            }
        }
    }

    fun replaceRecoveryKey() {
        val token = operation
        update("Replacing recovery key…") {
            val replacement = repository.replaceRecoveryKey()
            if (token == operation) recoveryKey = replacement
        }
    }

    // MARK: automatic backup (iOS VaultModel extension)

    fun scheduleAutomaticBackup() {
        automaticTask?.cancel()
        val id = library?.id ?: return
        automaticNeedsPreparation = true
        automaticTask =
            viewModelScope.launch {
                try {
                    val status = automatic.status(id)
                    if (library?.id == id) {
                        automaticStatus = status
                        if (status != null) automaticNeedsPreparation = automatic.needsPreparation(id, repository.backupRevision())
                    }
                    if (status == null) return@launch
                    delay(2_000) // changes settle for two seconds
                    prepareAutomaticBackup()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (library?.id == id) automaticMessage = e.vaultMessage
                }
            }
    }

    fun enableAutomaticBackup(tree: Uri) {
        val id = library?.id ?: return
        viewModelScope.launch {
            try {
                automatic.configure(id, tree.toString())
                automaticMessage = null
                scheduleAutomaticBackup()
            } catch (e: Exception) {
                automaticMessage = e.vaultMessage
            }
        }
    }

    fun disableAutomaticBackup() {
        val id = library?.id ?: return
        automaticTask?.cancel()
        viewModelScope.launch {
            try {
                automatic.disable(id)
                automaticStatus = null
                automaticMessage = null
            } catch (e: Exception) {
                automaticMessage = e.vaultMessage
            }
        }
    }

    suspend fun prepareAutomaticBackup() {
        if (automaticWorking) return
        val id = library?.id ?: return
        if (busy) {
            scheduleAutomaticBackup()
            return
        }
        automaticWorking = true
        var prepared = false
        try {
            val revision = repository.backupRevision()
            if (!automatic.needsPreparation(id, revision)) return
            val output = File(automatic.preparing(), UUID.randomUUID().upper() + ".vault7")
            try {
                val (actual, actualRevision) = repository.automaticBackup(output)
                if (actual != id) return
                // Once fully prepared, keep the encrypted artifact even if the UI just locked.
                automatic.enqueue(output, id, actualRevision, "vault7")
                prepared = true
            } finally {
                output.delete()
            }
            if (library?.id == id) {
                automaticStatus = automatic.status(id)
                automaticNeedsPreparation = false
                automaticMessage = null
            }
            BackupWorker.schedule(application, automatic.nextAttempt())
            retryAutomaticBackups()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (library?.id == id) automaticMessage = "Backup needs another attempt: " + e.vaultMessage
        } finally {
            automaticWorking = false
            if (prepared && library != null) scheduleAutomaticBackup()
        }
    }

    fun backUpNow() {
        viewModelScope.launch {
            prepareAutomaticBackup()
            retryAutomaticBackups(force = true)
        }
    }

    suspend fun retryAutomaticBackups(force: Boolean = false) {
        deliveryTask?.takeIf { it.isActive }?.let {
            it.join()
            return
        }
        val job =
            viewModelScope.launch {
                try {
                    automatic.deliverPending(force = force)
                    library?.id?.let { automaticStatus = automatic.status(it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (library != null) automaticMessage = e.vaultMessage
                }
                BackupWorker.schedule(application, runCatching { automatic.nextAttempt() }.getOrNull())
            }
        deliveryTask = job
        job.join()
    }

    fun foreground() {
        store.refresh()
        viewModelScope.launch { retryAutomaticBackups() }
    }

    override fun onCleared() = store.close()
}
