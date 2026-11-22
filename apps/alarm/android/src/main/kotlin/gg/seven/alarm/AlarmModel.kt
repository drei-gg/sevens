package gg.seven.alarm

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.alarm.core.*
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The screens' model: the shared [AlarmEngine], the store, and the drafts that survive rotation.
 */
class AlarmModel(app: Application) : AndroidViewModel(app) {
    val engine = app.engine
    val store =
        SevenStore(
            app,
            BuildConfig.PERSONAL_UNLOCK,
            sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY),
            viewModelScope,
        )

    /** The routine being edited; null when no editor is open. */
    val draft = MutableStateFlow<Routine?>(null)

    /** The history record being corrected. */
    val eventDraft = MutableStateFlow<LoopEvent?>(null)

    /** A short note for the snackbar, with an optional Undo. */
    data class Note(val text: String, val undo: (() -> Unit)? = null)

    val note = MutableStateFlow<Note?>(null)
    val importPreview = MutableStateFlow<LoopArchive?>(null)
    private var pendingTemplate: ByteArray? = null

    fun prepareTemplate(routine: Routine, ready: () -> Unit) = run {
        try {
            pendingTemplate = core { routineTemplate(routine) }
            ready()
        } catch (e: Exception) {
            error.value = e.sentence
        }
    }

    fun exportTemplate(uri: Uri?) {
        if (uri == null) {
            pendingTemplate = null
            return
        }
        val bytes =
            pendingTemplate
                ?: kotlin.run {
                    engine.error.value = "Choose the routine again to save a template."
                    return
                }
        run {
            try {
                write(uri, bytes)
                if (pendingTemplate === bytes) pendingTemplate = null
                note.value =
                    Note("Routine template saved. Import it from Settings on either platform.")
            } catch (e: Exception) {
                error.value = e.sentence
            }
        }
    }

    init {
        // iOS presents expiry in Settings only, never as a sheet that could cover a ringing alarm
        // or a mission, so the shared trial-ended sheet is marked as seen.
        store.trialEndedShown = true
        viewModelScope.launch {
            engine.reload()
            engine.synchronize()
        }
    }

    val paid
        get() = store.paidFeaturesAvailable

    fun run(block: suspend AlarmEngine.() -> Unit) {
        viewModelScope.launch { engine.block() }
    }

    fun resume() {
        store.refresh()
        run {
            reload()
            synchronize()
        }
    }

    fun open(id: UUID) = run {
        try {
            openNative(id)
        } catch (e: Exception) {
            error.value = e.sentence
        }
    }

    fun save(routine: Routine, onSaved: () -> Unit, onFailed: (String) -> Unit) = run {
        if (save(routine, paid)) onSaved()
        else {
            onFailed(error.value ?: "Couldn’t save.")
            error.value = null
        }
    }

    fun deleteEvent(event: LoopEvent, onDeleted: () -> Unit = {}) = run {
        if (mutate { a -> a.copy(events = a.events.filter { it.id != event.id }) }) {
            onDeleted()
            note.value =
                Note("Record deleted") {
                    this@AlarmModel.run {
                        mutate { a ->
                            if (a.events.any { it.id == event.id }) a
                            else a.copy(events = a.events + event)
                        }
                    }
                }
        }
    }

    fun saveEvent(event: LoopEvent, onSaved: () -> Unit) = run {
        // Corrections are marked as manually recorded.
        val saved = event.copy(kind = EventKind.MANUAL)
        if (mutate { a -> a.copy(events = a.events.map { if (it.id == saved.id) saved else it }) })
            onSaved()
    }

    // MARK: files

    private suspend fun write(uri: Uri, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(bytes)
            } ?: throw LoopFailure("Couldn’t open that file.")
        }

    private fun export(uri: Uri, done: String, bytes: suspend AlarmEngine.() -> ByteArray) = run {
        try {
            write(uri, bytes())
            note.value = Note(done)
        } catch (e: Exception) {
            error.value = e.sentence
        }
    }

    fun exportBackup(uri: Uri) =
        export(uri, "Backup saved") { core { backupEncode(archive.value) } }

    fun exportCsv(uri: Uri) =
        export(uri, "History saved") { backupCsv(archive.value).toByteArray() }

    fun exportDiagnostics(uri: Uri) =
        export(uri, "Diagnostics saved") { diagnostics().toByteArray() }

    fun previewImport(uri: Uri) = run {
        try {
            val bytes =
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input
                        ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            if (out.size() > maxBackupBytes)
                                throw LoopFailure("This backup is larger than 32 MB.")
                        }
                        out.toByteArray()
                    } ?: throw LoopFailure("Couldn’t open that file.")
                }
            importPreview.value = decodeArchive(bytes)
        } catch (e: Exception) {
            error.value = e.sentence
        }
    }

    fun confirmImport() = run {
        val incoming = importPreview.value ?: return@run
        importPreview.value = null
        if (mutate { core { backupMerge(incoming, it) } })
            note.value = Note("Backup imported. New routines are off.")
    }

    /**
     * The troubleshooting report (iOS AlarmDiagnostics): allowlisted fields only, never names,
     * schedules, codes, phrases, history, identifiers or raw error messages. Android can't list its
     * alarms, so the native count is the saved and wanted registrations whose alarm is armed.
     */
    fun diagnostics(): String {
        val context = getApplication<Application>()
        val archive = engine.archive.value
        val desired = runCatching { engine.desired() }.getOrNull()
        val candidates = (archive.installed + desired.orEmpty()).distinctBy { it.id }
        val live = candidates.filter { engine.driver.isLive(it) }.mapTo(HashSet()) { it.id }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val report =
            sortedMapOf<String, Any?>(
                "formatVersion" to 1,
                "generatedAt" to
                    DateTimeFormatter.ISO_INSTANT.format(
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    ),
                "appVersion" to (info.versionName ?: "Unknown"),
                "build" to
                    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toString(),
                "operatingSystem" to
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "previewMode" to engine.isPreview,
                "alarmPermission" to engine.driver.status,
                "routineCount" to archive.routines.size,
                "enabledRoutineCount" to archive.routines.count { it.enabled },
                "localReceiptCount" to archive.installed.size,
                "desiredRegistrationCount" to desired?.size,
                "nativeRegistrationCount" to live.size,
                "missingRegistrationCount" to desired?.count { it.id !in live },
                "scheduleValidationSucceeded" to (desired != null),
                "nativeQuerySucceeded" to true,
                "hasLocalError" to (engine.error.value != null),
                "hasLoadError" to (engine.loadError.value != null),
            )
        val json = JSONObject()
        report.forEach { (key, value) -> if (value != null) json.put(key, value) }
        return json.toString(2)
    }

    override fun onCleared() = store.close()
}
