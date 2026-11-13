package gg.seven.fast

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.fast.core.*
import gg.seven.kit.AccessStore
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FastApplication : Application() {
    val repository by lazy { ArchiveRepository(this) }
    val access by lazy { AccessStore(this, BuildConfig.PERSONAL_UNLOCK) }
    val purchaseSource by lazy { purchases(this) }
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
                            FastArchive()
                        else
                            file.openRead().use {
                                ArchiveCodec.decode(
                                    it.readBytes().toString(Charsets.UTF_8),
                                    nowMillis(),
                                )
                            }
                } catch (e: Exception) {
                    failure.value =
                        "Tracking file could not be opened: ${e.message}. The original file is preserved. Export it before recovery."
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

class FastModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FastApplication
    val repository = app.repository
    val archive = repository.state.asStateFlow()
    val loadFailure = repository.failure.asStateFlow()
    val purchaseSource = app.purchaseSource
    val purchase = purchaseSource.state
    val access = app.access
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
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
        purchaseSource.refresh()
        archive.value?.let(::updateSurfaces)
    }

    private fun updateSurfaces(a: FastArchive) {
        runCatching { ReminderScheduler.reconcile(app, a) }
            .onFailure {
                message.value =
                    "Your records are saved. Android could not update reminders or the timer surface; reopen the app to retry."
            }
    }

    val canCreate
        get() = access.canCreate(purchase.value, System.currentTimeMillis())

    private fun act(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                message.value = e.message ?: "Could not save. Try again."
            } finally {
                busy.value = false
            }
        }
    }

    fun mutate(change: (FastArchive) -> FastArchive, onSaved: () -> Unit = {}) = act {
        val saved =
            repository.update { current ->
                val next = change(current)
                if (!canCreate) {
                    fun <T> additions(before: List<T>, after: List<T>, id: (T) -> String): Boolean {
                        val known = before.mapTo(HashSet(), id)
                        return after.any { id(it) !in known }
                    }
                    require(
                        !additions(current.fasts, next.fasts) { it.id } &&
                            !additions(current.journal, next.journal) { it.id } &&
                            !additions(current.metrics, next.metrics) { it.id } &&
                            !additions(current.plans, next.plans) { it.id } &&
                            !additions(current.restDays, next.restDays) { it.id } &&
                            !additions(current.presets, next.presets) { it.id }
                    ) {
                        "Start the free trial or unlock to add records. Your existing records remain editable and exportable."
                    }
                }
                next
            }
        updateSurfaces(saved)
        onSaved()
    }

    fun startTrial() {
        runCatching {
                access.startTrial()
                now.value = nowMillis()
            }
            .onFailure { message.value = it.message }
    }

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
