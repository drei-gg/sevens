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

    class TextReview(val item: VaultItem, val original: String, val initialName: String) : Presentation {
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

    /** A system picker, share sheet or prompt Vault opened is in front: the lock waits, bounded. */
    val systemFlow = SystemFlowGuard()

    /** Call as a system picker, share sheet or prompt is launched. */
    fun beginSystemFlow() = systemFlow.begin()

    /** Call when its result arrives (Vault's return to the front also ends it). */
    fun endSystemFlow() = systemFlow.end()

    private fun words(id: Int, vararg args: Any) = application.getString(id, *args)

    private val Throwable.shown: String
        get() = vaultMessage(application)

    /** "Receipt text": the default name for a note recognized from [item]. */
    private fun recognizedName(item: VaultItem) = words(R.string.review_default_name, item.name.substringBeforeLast('.'))

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
                error = e.shown
            }
            try {
                automatic.prepare()
            } catch (e: Exception) {
                automaticMessage = e.shown
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
        systemFlow.end()
        presentation = null
        error = null
        importResult = ""
        // Stay busy until the repository has discarded keys and any in-flight operation finished.
        busy = true
        progress = words(R.string.progress_locking)
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
                if (token == operation) error = e.shown
            } finally {
                if (token == operation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    /** A change to the open vault; the UI follows the committed library only if still unlocked. */
    fun update(label: String = words(R.string.progress_saving), then: () -> Unit = {}, action: suspend () -> Unit) {
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
                if (token == operation) error = e.shown
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
            error = words(R.string.create_gated)
            return
        }
        perform(words(R.string.progress_creating), onCreated) {
            val result = repository.create(name, secret, kind)
            result.library to result.recoveryKey
        }
    }

    fun unlock(credential: String, recovery: Boolean, kind: VaultCredentialKind) =
        perform(words(R.string.progress_opening)) { repository.unlock(credential, recovery, kind) to null }

    private fun displayName(uri: Uri): String =
        runCatching {
                application.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: (uri.lastPathSegment?.substringAfterLast('/') ?: words(R.string.import_unnamed))

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
                        words(
                            if (fromPhotos) R.string.progress_loading_encrypting else R.string.progress_encrypting,
                            index + 1,
                            uris.size,
                        )
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
                            if (fromPhotos) words(R.string.import_failed_item, index + 1, e.shown)
                            else words(R.string.import_failed_file, name, e.shown)
                    }
                }
                if (token != operation) return@launch
                library = runCatching { repository.snapshot() }.getOrNull()
                scheduleAutomaticBackup()
                importResult =
                    application.resources.getQuantityString(
                        if (fromPhotos) R.plurals.import_result_photos else R.plurals.import_result_files,
                        successes,
                        successes,
                    )
                if (failures.isNotEmpty())
                    error =
                        application.resources.getQuantityString(
                            if (fromPhotos) R.plurals.import_failed_photos else R.plurals.import_failed_files,
                            failures.size,
                            failures.size,
                        ) + "\n" + failures.joinToString("\n")
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
        progress = words(R.string.progress_encrypting, 1, 1)
        val token = operation
        viewModelScope.launch {
            try {
                repository.importFile(file.path, file.name, "public.jpeg", folder)
                if (token == operation) {
                    library = repository.snapshot()
                    scheduleAutomaticBackup()
                    importResult = application.resources.getQuantityString(R.plurals.import_result_camera, 1, 1)
                }
            } catch (e: Exception) {
                if (token == operation) error = e.shown
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
        update(words(R.string.progress_preview)) {
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
            error = plan.issue?.wording?.resolve(application)
            return
        }
        presentation = RecognitionQueue(plan)
    }

    fun recognizeBatchItem(queue: RecognitionQueue) {
        if (busy || presentation !== queue || queue.stopped) return
        val item = queue.item ?: return
        val token = operation
        queue.failure = null
        update(words(R.string.progress_recognizing_file, queue.index + 1, queue.plan.items.size)) {
            try {
                val file = repository.export(item.id)
                val text =
                    try {
                        if (token != operation || queue.stopped)
                            throw kotlinx.coroutines.CancellationException()
                        if (Types.isPdf(item.contentType, item.name)) {
                            val count = PdfRecognition.pageCount(file)
                            if (count !in 1u..queue.plan.maximumPdfPages)
                                throw VaultMessage(
                                    Wording.count(R.plurals.error_pdf_too_long_for_queue, queue.plan.maximumPdfPages.toInt())
                                )
                            PdfRecognition.read(getApplication(), file, 1u, count) { page ->
                                kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.Main.immediate
                                ) {
                                    if (token != operation || queue.stopped)
                                        throw kotlinx.coroutines.CancellationException()
                                    progress = words(R.string.progress_file_page, queue.index + 1, page.toInt(), count.toInt())
                                }
                            }
                        } else
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                val bitmap =
                                    Images.decode(file, 2400)
                                        ?: throw VaultMessage(Wording.of(R.string.error_image_unreadable))
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
                    queue.name = recognizedName(item)
                    queue.ready = true
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                if (token == operation && !queue.stopped) throw cancel
            } catch (e: Exception) {
                if (token == operation && !queue.stopped) queue.failure = e.shown
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
            update(words(R.string.progress_reading_pdf)) {
                val file = repository.export(item.id)
                val count =
                    try {
                        PdfRecognition.pageCount(file)
                    } finally {
                        file.delete()
                    }
                if (count == 0u) throw VaultMessage(VaultIssue.NoReadablePages.wording)
                if (token == operation) presentation = Presentation.PdfRange(item, count)
            }
            return
        }
        if (!Types.isImage(item.contentType, item.name)) return
        val token = operation
        update(words(R.string.progress_recognizing_image)) {
            val file = repository.export(item.id)
            val text =
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val bitmap =
                            Images.decode(file, 2400)
                                ?: throw VaultMessage(Wording.of(R.string.error_image_unreadable))
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
            if (token == operation) presentation = Presentation.TextReview(item, text, recognizedName(item))
        }
    }

    fun recognizePdf(draft: Presentation.PdfRange) {
        if (busy || presentation !== draft || draft.plan.issue != null) return
        val token = operation
        val first = draft.first.toUInt()
        val last = draft.last.toUInt()
        update(words(R.string.progress_recognizing_pdf)) {
            val file = repository.export(draft.item.id)
            val text =
                try {
                    PdfRecognition.read(getApplication(), file, first, last) { page ->
                        kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.Main.immediate
                        ) {
                            if (token != operation)
                                throw kotlinx.coroutines.CancellationException("Vault locked")
                            progress = words(R.string.progress_recognizing_page, page.toInt(), draft.pageCount.toInt())
                        }
                    }
                } finally {
                    file.delete()
                }
            if (token == operation) presentation = Presentation.TextReview(draft.item, text, recognizedName(draft.item))
        }
    }

    fun export(ids: Set<UUID>) {
        if (ids.isEmpty()) return
        val token = operation
        update(words(R.string.progress_originals)) {
            val files = mutableListOf<File>()
            val failures = mutableListOf<String>()
            for (id in ids.sortedBy { it.upper() }) {
                if (token != operation) return@update
                try {
                    files += repository.export(id)
                } catch (e: Exception) {
                    failures += e.shown
                }
            }
            if (token != operation) return@update
            if (files.isNotEmpty()) presentation = Presentation.Files(files)
            if (failures.isNotEmpty())
                error =
                    application.resources.getQuantityString(R.plurals.export_failed, failures.size, failures.size) +
                        "\n" + failures.joinToString("\n")
        }
    }

    fun backup() {
        val token = operation
        update(words(R.string.progress_backup)) {
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
        perform(words(R.string.progress_verifying_backup), onRestored) {
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
        systemFlow.begin()
        update(then = { showNotice(words(R.string.biometric_on_notice)) }) {
            try {
                val (id, secret) = repository.custodyMaterial()
                BiometricKey.enable(activity, id, secret)
                hasBiometricKey = true
            } finally {
                systemFlow.end()
            }
        }
    }

    fun disableBiometrics() {
        BiometricKey.remove(application)
        hasBiometricKey = false
        showNotice(words(R.string.biometric_off))
    }

    fun unlockBiometrics(activity: FragmentActivity) {
        if (BiometricKey.storedId(application) == null) return
        systemFlow.begin()
        perform(words(R.string.progress_authenticating)) {
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
                systemFlow.end()
            }
        }
    }

    fun replaceRecoveryKey() {
        val token = operation
        update(words(R.string.progress_replacing_recovery)) {
            val replacement = repository.replaceRecoveryKey()
            if (token == operation) recoveryKey = replacement
        }
    }

    /**
     * "I’ve saved it": the index stops asking for a replacement, then the key leaves memory. A
     * failed save keeps the key on screen to retry.
     */
    fun confirmRecoveryKey() {
        val token = operation
        update(then = { if (token == operation) recoveryKey = null }) { repository.confirmRecoveryKey() }
    }

    /**
     * The open vault's recovery key was issued but never confirmed as saved (the app locked or
     * closed first): it must be replaced before the library opens.
     */
    val needsRecoveryReplacement: Boolean
        get() = library?.recoveryUnconfirmed == true && recoveryKey == null

    /**
     * Restored with its recovery key: the backup's own secret may already open another vault here,
     * so a new one must be set (with the usual duplicate check) before the library opens.
     */
    val needsNewSecret: Boolean
        get() = library?.secretChangeRequired == true

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
                    if (library?.id == id) automaticMessage = e.shown
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
                automaticMessage = e.shown
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
                automaticMessage = e.shown
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
                automaticMessage = words(R.string.automatic_retry, e.shown)
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
                    if (library != null) automaticMessage = e.shown
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
