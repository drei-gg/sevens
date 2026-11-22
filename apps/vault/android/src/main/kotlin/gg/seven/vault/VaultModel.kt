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

    class PdfRange(val item: VaultItem, val pageCount: UInt) : Presentation {
        var first by mutableStateOf("1")
        var last by mutableStateOf(minOf(pageCount, 20u).toString())
        val plan
            get() =
                recognitionPlan(pageCount, first.toUIntOrNull() ?: 0u, last.toUIntOrNull() ?: 0u)
    }

    class TextReview(val item: VaultItem, val original: String) : Presentation {
        val initialName = item.name.substringBeforeLast('.') + " text"
        var name by mutableStateOf(initialName)
        var text by mutableStateOf(original)
    }
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
        (presentation as? RecognitionQueue)?.let {
            it.stopped = true
            it.text = ""
        }
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

    private fun perform(
        label: String,
        onSuccess: () -> Unit = {},
        action: suspend () -> Pair<VaultLibrary, String?>,
    ) {
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
                onSuccess()
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
            } catch (e: BiometricKey.Cancelled) {} catch (e: Exception) {
                if (token == operation) error = e.vaultMessage
            } finally {
                if (token == operation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    fun create(
        name: String,
        secret: String,
        kind: VaultCredentialKind,
        onCreated: () -> Unit = {},
    ) {
        if (!store.paidFeaturesAvailable) {
            error = "Start the trial or unlock to add a vault."
            return
        }
        perform("Creating encrypted vault…", onCreated) {
            val result = repository.create(name, secret, kind)
            result.library to result.recoveryKey
        }
    }

    fun unlock(credential: String, recovery: Boolean, kind: VaultCredentialKind) =
        perform("Opening vault…") { repository.unlock(credential, recovery, kind) to null }

    private fun displayName(uri: Uri): String =
        runCatching {
                application.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "Imported file")

    /**
     * Files (Storage Access Framework) or the Photo Picker: each read once, straight from its
     * descriptor.
     */
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
                    progress =
                        if (fromPhotos) "Loading and encrypting ${index + 1} of ${uris.size}…"
                        else "Encrypting ${index + 1} of ${uris.size}…"
                    val name = displayName(uri)
                    try {
                        val type = Types.identifier(application.contentResolver.getType(uri), name)
                        val descriptor =
                            application.contentResolver.openFileDescriptor(uri, "r")
                                ?: throw VaultException.MissingFile()
                        descriptor.use { repository.importDescriptor(it.fd, name, type, folder) }
                        successes += 1
                    } catch (e: Exception) {
                        failures +=
                            if (fromPhotos) "Item ${index + 1}: ${e.vaultMessage}"
                            else "$name: ${e.vaultMessage}"
                    }
                }
                if (token != operation) return@launch
                library = runCatching { repository.snapshot() }.getOrNull()
                scheduleAutomaticBackup()
                importResult =
                    if (fromPhotos) "$successes imported. Originals stay in your gallery."
                    else "$successes imported. Originals stay where they were."
                if (failures.isNotEmpty())
                    error =
                        (if (fromPhotos) "${failures.size} imports failed:\n"
                        else "${failures.size} could not be imported:\n") +
                            failures.joinToString("\n")
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

    fun prepareRecognitionQueue(ids: Set<UUID>) {
        if (busy) return
        val items = library?.items ?: return
        val supported =
            items
                .filter {
                    Types.isImage(it.contentType, it.name) || Types.isPdf(it.contentType, it.name)
                }
                .map { it.id }
        val plan = recognitionBatchPlan(items, ids.toList(), supported)
        if (plan.issue != null) {
            error = plan.issue
            return
        }
        presentation = RecognitionQueue(plan)
    }

    fun recognizeBatchItem(queue: RecognitionQueue) {
        if (busy || presentation !== queue || queue.stopped) return
        val item = queue.item ?: return
        val token = operation
        queue.failure = null
        update("Recognizing file ${queue.index + 1} of ${queue.plan.items.size}…") {
            try {
                val file = repository.export(item.id)
                val text =
                    try {
                        if (token != operation || queue.stopped)
                            throw kotlinx.coroutines.CancellationException()
                        if (Types.isPdf(item.contentType, item.name)) {
                            val count = PdfRecognition.pageCount(file)
                            require(count in 1u..queue.plan.maximumPdfPages) {
                                "This PDF needs the individual page-range action (up to ${queue.plan.maximumPdfPages} pages). Skip it here."
                            }
                            PdfRecognition.read(getApplication(), file, 1u, count) { page ->
                                kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.Main.immediate
                                ) {
                                    if (token != operation || queue.stopped)
                                        throw kotlinx.coroutines.CancellationException()
                                    progress = "File ${queue.index + 1}: page $page of $count…"
                                }
                            }
                        } else
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                val bitmap =
                                    Images.decode(file, 2400)
                                        ?: error("This image could not be read.")
                                try {
                                    gg.seven.ocr.OnDeviceTextRecognizer.open(getApplication()).use {
                                        it.text(bitmap)
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                    } finally {
                        file.delete()
                    }
                if (token == operation && presentation === queue && !queue.stopped) {
                    queue.text = recognitionReviewText(text)
                    queue.name = item.name.substringBeforeLast('.') + " text"
                    queue.ready = true
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                if (token == operation && !queue.stopped) throw cancel
            } catch (e: Exception) {
                if (token == operation && !queue.stopped) queue.failure = e.vaultMessage
            }
        }
    }

    fun saveBatchItem(queue: RecognitionQueue) {
        if (
            busy ||
                presentation !== queue ||
                !queue.ready ||
                queue.stopped ||
                !store.paidFeaturesAvailable
        )
            return
        val item = queue.item ?: return
        val name = queue.name
        val text = queue.text
        update(then = { queue.next(true) }) {
            repository.addNote(name, recognitionReviewText(text), item.folder)
        }
    }

    fun recognizeText(item: VaultItem) {
        if (busy) return
        if (Types.isPdf(item.contentType, item.name)) {
            val token = operation
            update("Reading PDF pages…") {
                val file = repository.export(item.id)
                val count =
                    try {
                        PdfRecognition.pageCount(file)
                    } finally {
                        file.delete()
                    }
                require(count > 0u) { "This PDF has no readable pages." }
                if (token == operation) presentation = Presentation.PdfRange(item, count)
            }
            return
        }
        if (!Types.isImage(item.contentType, item.name)) return
        val token = operation
        update("Recognizing text on this device…") {
            val file = repository.export(item.id)
            val text =
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val bitmap =
                            Images.decode(file, 2400) ?: error("This image could not be read.")
                        try {
                            gg.seven.ocr.OnDeviceTextRecognizer.open(getApplication()).use {
                                it.text(bitmap)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } finally {
                    file.delete()
                }
            if (token == operation) presentation = Presentation.TextReview(item, text)
        }
    }

    fun recognizePdf(draft: Presentation.PdfRange) {
        if (busy || presentation !== draft || draft.plan.issue != null) return
        val token = operation
        val first = draft.first.toUInt()
        val last = draft.last.toUInt()
        update("Recognizing PDF on this device…") {
            val file = repository.export(draft.item.id)
            val text =
                try {
                    PdfRecognition.read(getApplication(), file, first, last) { page ->
                        kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.Main.immediate
                        ) {
                            if (token != operation)
                                throw kotlinx.coroutines.CancellationException("Vault locked")
                            progress = "Recognizing page $page of ${draft.pageCount}…"
                        }
                    }
                } finally {
                    file.delete()
                }
            if (token == operation) presentation = Presentation.TextReview(draft.item, text)
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
            if (failures.isNotEmpty())
                error =
                    "${failures.size} exports failed. Successful originals are ready.\n" +
                        failures.joinToString("\n")
        }
    }

    fun backup() {
        val token = operation
        update("Verifying and preparing encrypted backup…") {
            val file = repository.backup()
            if (token == operation) presentation = Presentation.Files(listOf(file), backup = true)
        }
    }

    fun restore(
        uri: Uri,
        credential: String,
        recovery: Boolean,
        kind: VaultCredentialKind,
        onRestored: () -> Unit = {},
    ) =
        perform("Verifying backup…", onRestored) {
            val descriptor =
                application.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw VaultException.MissingFile()
            descriptor.use { repository.restoreDescriptor(it.fd, credential, recovery, kind) } to
                null
        }

    fun cleanPresentation() {
        (presentation as? RecognitionQueue)?.let {
            it.stopped = true
            it.text = ""
        }
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
                        if (status != null)
                            automaticNeedsPreparation =
                                automatic.needsPreparation(id, repository.backupRevision())
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
            if (library?.id == id)
                automaticMessage = "Backup needs another attempt: " + e.vaultMessage
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
        deliveryTask
            ?.takeIf { it.isActive }
            ?.let {
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
                BackupWorker.schedule(
                    application,
                    runCatching { automatic.nextAttempt() }.getOrNull(),
                )
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
