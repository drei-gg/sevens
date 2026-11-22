package gg.seven.fast

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.fast.core.*
import gg.seven.kit.SevenStore
import gg.seven.kit.schedule.ReminderHost
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FastApplication : Application(), ReminderHost {
    val repository by lazy { ArchiveRepository(this) }
    override val reminderScheduler by lazy { FastReminders.create(this) }

    override fun onCreate() {
        super.onCreate()
        reminderScheduler.maintain()
    }

    /** Lives as long as the process: the Wear bridge reads access without an activity. */
    private val appScope = MainScope()

    /**
     * The trial, the unlock and the launch sheets (SevenKit). Its trial is the same `seven-trial`
     * preference the earlier AccessStore wrote, so an existing trial or unlock carries over.
     */
    val store by lazy {
        SevenStore(this, BuildConfig.PERSONAL_UNLOCK, purchases(this), appScope).also {
            // Before SevenKit's gate, Fast showed its own welcome dialog once; don't show the
            // first-launch page again to someone who already saw that.
            if (!it.beforeYouBuyShown && getSharedPreferences("welcome", MODE_PRIVATE).getBoolean("seen", false))
                it.beforeYouBuyShown = true
        }
    }
    val access get() = store.access
    val purchaseSource get() = store.purchases
}

class ArchiveRepository(
    context: Context,
    private val file: AtomicFile = AtomicFile(File(context.filesDir, "fast.json")),
) {
    private val lock = Mutex()
    val state = MutableStateFlow<FastArchive?>(null)
    val failure = MutableStateFlow<String?>(null)

    suspend fun load() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (state.value != null || failure.value != null) return@withLock
                try {
                    state.value =
                        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
                            FastArchive.empty()
                        else
                            file.openRead().use {
                                ArchiveCodec.decode(
                                    it.readBytes().toString(Charsets.UTF_8),
                                    nowMillis(),
                                )
                            }
                } catch (e: Exception) {
                    failure.value =
                        "Tracking file could not be opened. ${e.explanation} The original file is preserved. Export it before recovery."
                }
            }
        }

    suspend fun update(change: (FastArchive) -> FastArchive): FastArchive =
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(failure.value == null) { "The archive is read-only until recovery" }
                val current = checkNotNull(state.value) { "Still opening your records" }
                val next = change(current).copy(companionRevision = newId()).validated(nowMillis())
                val bytes = ArchiveCodec.encode(next).toByteArray()
                require(bytes.size <= ArchiveCodec.maxBytes) { "Archive exceeds 25 MB" }
                val stream = file.startWrite()
                try {
                    stream.write(bytes)
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    throw e
                }
                state.value = next
                next
            }
        }

    suspend fun rawBytes(): ByteArray =
        withContext(Dispatchers.IO) { lock.withLock { file.openRead().use { it.readBytes() } } }
}

/**
 * Whether [after] holds a record [before] didn't, other than the [restoring] ones (records put
 * back by Undo with their original ID). Without the trial or unlock, such a change is refused.
 */
internal fun addsNewRecords(
    before: FastArchive,
    after: FastArchive,
    restoring: Set<java.util.UUID> = emptySet(),
): Boolean {
    fun <T> added(old: List<T>, new: List<T>, id: (T) -> java.util.UUID): Boolean {
        val known = old.mapTo(HashSet(), id)
        return new.any { id(it) !in known && id(it) !in restoring }
    }
    return added(before.fasts, after.fasts) { it.id } ||
        added(before.journal, after.journal) { it.id } ||
        added(before.metrics, after.metrics) { it.id } ||
        added(before.plans, after.plans) { it.id } ||
        added(before.restDays, after.restDays) { it.id } ||
        added(before.presets, after.presets) { it.id }
}

class FastModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FastApplication
    val repository = app.repository
    val archive = repository.state.asStateFlow()
    val loadFailure = repository.failure.asStateFlow()
    val store = app.store
    val purchase = store.purchase
    val access = store.access
    val message = MutableStateFlow<String?>(null)

    /** A short note with Undo, for changes that are easy to take back (a deleted drink). */
    data class Undoable(val text: String, val undo: () -> Unit)

    val undoable = MutableStateFlow<Undoable?>(null)
    /** Guards against overlapping writes; never shown. */
    val busy = MutableStateFlow(false)

    /** Shown in the UI: only once a write has run for 400 ms, so a quick save never blinks the screen. */
    val working = MutableStateFlow(false)
    val importPreview = MutableStateFlow<FastArchive?>(null)
    val now = MutableStateFlow(nowMillis())

    init {
        viewModelScope.launch {
            repository.load()
            archive.value?.let(::updateSurfaces)
        }
        viewModelScope.launch {
            while (isActive) {
                now.value = nowMillis()
                access.observeTime()
                delay(1000)
            }
        }
    }

    fun refresh() {
        now.value = nowMillis()
        store.refresh()
        archive.value?.let(::updateSurfaces)
    }

    private fun updateSurfaces(@Suppress("UNUSED_PARAMETER") a: FastArchive) {
        // Reads the saved archive, which `a` already is: the model persists before it publishes.
        viewModelScope.launch {
            runCatching { FastReminders.reconcile(app) }
                .onFailure {
                    message.value =
                        "Your records are saved. Android could not update reminders or the timer surface; reopen the app to retry."
                }
        }
    }

    val canCreate
        get() = access.canCreate(purchase.value, System.currentTimeMillis())

    private fun act(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        val reveal =
            viewModelScope.launch {
                kotlinx.coroutines.delay(400)
                working.value = true
            }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                message.value = e.explanation
            } finally {
                reveal.cancel()
                working.value = false
                busy.value = false
            }
        }
    }

    /**
     * Saves [change]. Without the trial or unlock, a change may correct, delete or end existing
     * records but not add new ones; [restoring] names records being put back by Undo (the same
     * ID and time as before), which are a restore rather than new records.
     */
    fun mutate(
        change: (FastArchive) -> FastArchive,
        onSaved: () -> Unit = {},
        restoring: Set<java.util.UUID> = emptySet(),
    ) = act {
        val saved =
            repository.update { current ->
                val next = change(current)
                require(canCreate || !addsNewRecords(current, next, restoring)) {
                    "Start the free trial or unlock to add records. Your existing records remain editable and exportable."
                }
                next
            }
        updateSurfaces(saved)
        onSaved()
    }

    /** Puts back a drink or measurement deleted moments ago, as the same record. */
    fun restoreMetric(entry: MetricEntry) =
        mutate(
            { current ->
                if (current.metrics.any { it.id == entry.id }) current
                else current.copy(metrics = current.metrics + entry)
            },
            restoring = setOf(entry.id),
        )

    fun export(uri: Uri, csv: Boolean = false) = act {
        withContext(Dispatchers.IO) {
            val bytes =
                if (loadFailure.value != null) repository.rawBytes()
                else {
                    val a = checkNotNull(archive.value)
                    (if (csv) ArchiveCodec.csv(a) else ArchiveCodec.encode(a)).toByteArray()
                }
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("Could not open destination")
        }
        message.value = "Export saved"
    }

    fun previewImport(uri: Uri) = act {
        val incoming =
            withContext(Dispatchers.IO) {
                val text =
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            require(output.size() <= ArchiveCodec.maxBytes) {
                                "Backup exceeds 25 MB"
                            }
                        }
                        val bytes = output.toByteArray()
                        require(bytes.size <= ArchiveCodec.maxBytes) { "Backup exceeds 25 MB" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Could not read backup")
                ArchiveCodec.decode(text, nowMillis())
            }
        ArchiveCodec.merge(incoming, checkNotNull(archive.value), nowMillis())
        importPreview.value = incoming
    }

    fun confirmImport() = act {
        val incoming = checkNotNull(importPreview.value)
        val saved = repository.update { ArchiveCodec.merge(incoming, it, nowMillis()) }
        importPreview.value = null
        updateSurfaces(saved)
        message.value = "Backup merged"
    }
}
