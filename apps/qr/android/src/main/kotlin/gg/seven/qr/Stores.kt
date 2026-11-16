package gg.seven.qr

import androidx.core.content.edit
import android.content.Context
import androidx.core.util.AtomicFile
import gg.seven.qr.core.HistoryDecoded
import gg.seven.qr.core.HistoryItem
import gg.seven.qr.core.KnownAddresses
import gg.seven.qr.core.KnownAddressesDecoded
import gg.seven.qr.core.historyExportCsv
import gg.seven.qr.core.historyExportJson
import gg.seven.qr.core.historyFileDecode
import gg.seven.qr.core.historyFileEncode
import gg.seven.qr.core.historyFileName
import gg.seven.qr.core.historySearch
import gg.seven.qr.core.knownAddressesAdd
import gg.seven.qr.core.knownAddressesFileDecode
import gg.seven.qr.core.knownAddressesFileEncode
import gg.seven.qr.core.knownAddressesFileName
import gg.seven.qr.core.knownAddressesRemove
import gg.seven.qr.core.listsMaxBytes
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * Files on the phone (SPEC §4.3, §4.4, §10), the counterparts of iOS HistoryStore,
 * KnownAddressStore and QRFiles/ListsStore. The file formats are the core's (`history.v1.json` and
 * `known.v1.json` byte for byte what the iPhone writes); this only reads, writes and serialises.
 * Writes are atomic (AtomicFile): a failed write never replaces the last good file. A file this
 * version can't read is never written or deleted; the store reads as empty and refuses changes.
 */

/** Atomic replace through AndroidX AtomicFile. */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val out = atomic.startWrite()
    try {
        out.write(bytes)
        atomic.finishWrite(out)
    } catch (error: Throwable) {
        atomic.failWrite(out)
        throw error
    }
}

/** `files/History`: History's folder, and the known addresses beside it. */
fun historyDirectory(context: Context): File = File(context.filesDir, "History")

data class HistoryState(
    /** Newest first. */
    val items: List<HistoryItem> = emptyList(),
    val readOnly: Boolean = false,
    val readOnlyReason: String? = null,
)

/**
 * History: one JSON file, newest first. Changes publish at once through [state]; the disk write is
 * coalesced over [debounceMillis] and retried on the next change or [flush] when it fails.
 */
class HistoryStore(
    private val directory: File?,
    private val scope: CoroutineScope,
    seed: List<HistoryItem> = emptyList(),
    private val debounceMillis: Long = 500,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(HistoryState(items = if (directory == null) newestFirst(seed) else emptyList()))
    val state: StateFlow<HistoryState> = mutableState.asStateFlow()
    private var loaded = directory == null
    private var dirty = false
    private var timer: Job? = null

    /** Successful disk writes so far (tests). */
    var writeCount = 0
        private set

    var lastWriteFailed = false
        private set

    private val file
        get() = directory?.let { File(it, historyFileName()) }

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val bytes = withContext(Dispatchers.IO) { file?.takeIf { it.isFile }?.let { runCatching { AtomicFile(it).readFully() }.getOrNull() } }
            ?: return
        mutableState.value =
            when (val decoded = historyFileDecode(bytes)) {
                is HistoryDecoded.Items -> HistoryState(items = newestFirst(decoded.items))
                else -> HistoryState(readOnly = true, readOnlyReason = QrText.historyReadOnlyReason)
            }
    }

    /** False only while read-only. */
    suspend fun add(item: HistoryItem): Boolean = change {
        val list = it.toMutableList()
        val clean = item.copy(note = cleanedNote(item.note))
        // Newest first; among equal dates the newer insertion goes first.
        val index = list.indexOfFirst { existing -> !existing.date.isAfter(clean.date) }.let { i -> if (i < 0) list.size else i }
        list.add(index, clean)
        list
    }

    /** A blank note clears it. False when there is no such item. */
    suspend fun updateNote(note: String?, id: UUID): Boolean = change { items ->
        if (items.none { it.id == id }) null else items.map { if (it.id == id) it.copy(note = cleanedNote(note)) else it }
    }

    /** How many were removed. */
    suspend fun delete(ids: Set<UUID>): Int {
        var removed = 0
        change { items ->
            val kept = items.filterNot { it.id in ids }
            removed = items.size - kept.size
            if (removed == 0) null else kept
        }
        return removed
    }

    suspend fun clear() {
        change { emptyList() }
    }

    /** Payload, note and type ID through the core, plus the type's display name ("Web link"). */
    suspend fun search(query: String): List<HistoryItem> = mutex.withLock {
        loadLocked()
        val items = mutableState.value.items
        val hits = historySearch(items, query)
        val needle = query.trim()
        if (needle.isEmpty()) return@withLock hits
        val ids = hits.map { it.id }.toSet()
        items.filter { it.id in ids || QrText.foldedContains(QrText.typeName(it.typeId), needle) }
    }

    suspend fun exportJson(known: KnownAddresses?): ByteArray = mutex.withLock {
        loadLocked()
        historyExportJson(mutableState.value.items, known)
    }

    suspend fun exportCsv(): String = mutex.withLock {
        loadLocked()
        historyExportCsv(mutableState.value.items)
    }

    /** Writes now if a change is waiting for the timer (the app going to the background). */
    suspend fun flush() = mutex.withLock {
        timer?.cancel()
        timer = null
        writeLocked()
    }

    /** Applies [transform] (null = nothing changed). False when read-only or unchanged. */
    private suspend fun change(transform: (List<HistoryItem>) -> List<HistoryItem>?): Boolean = mutex.withLock {
        loadLocked()
        val current = mutableState.value
        if (current.readOnly) return@withLock false
        val next = transform(current.items) ?: return@withLock false
        mutableState.value = current.copy(items = next)
        dirty = true
        if (directory != null && timer == null)
            timer = scope.launch {
                delay(debounceMillis)
                mutex.withLock {
                    timer = null
                    writeLocked()
                }
            }
        true
    }

    private suspend fun writeLocked() {
        val target = file ?: return
        val state = mutableState.value
        if (!dirty || state.readOnly) return
        val ok = withContext(Dispatchers.IO) { runCatching { writeAtomically(target, historyFileEncode(state.items)) }.isSuccess }
        if (ok) {
            dirty = false
            lastWriteFailed = false
            writeCount++
        } else lastWriteFailed = true
    }

    companion object {
        /** The longest useful note; longer input is cut. */
        const val MAX_NOTE_LENGTH = 2_000

        fun newestFirst(items: List<HistoryItem>): List<HistoryItem> = historySearch(items, "")

        fun cleanedNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }?.let { QrText.prefix(it, MAX_NOTE_LENGTH) }
    }
}

/**
 * The addresses the person said they know (SPEC §4.4): `known.v1.json` beside History. Writes are
 * immediate (the file is tiny and changes by a tap).
 */
class KnownAddressStore(private val directory: File?, seed: KnownAddresses = KnownAddresses()) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(seed)
    val addresses: StateFlow<KnownAddresses> = mutableState.asStateFlow()
    private var loaded = directory == null
    var readOnly = false
        private set

    private val file
        get() = directory?.let { File(it, knownAddressesFileName()) }

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val bytes = withContext(Dispatchers.IO) { file?.takeIf { it.isFile }?.let { runCatching { AtomicFile(it).readFully() }.getOrNull() } } ?: return
        when (val decoded = knownAddressesFileDecode(bytes)) {
            is KnownAddressesDecoded.Addresses -> mutableState.value = decoded.known
            else -> readOnly = true
        }
    }

    suspend fun add(domain: String, at: Instant = Instant.now()): Boolean = mutate { knownAddressesAdd(it, domain, at) }

    suspend fun remove(domain: String): Boolean = mutate { knownAddressesRemove(it, domain) }

    suspend fun removeAll(): Boolean = mutate { KnownAddresses() }

    private suspend fun mutate(change: (KnownAddresses) -> KnownAddresses): Boolean = mutex.withLock {
        loadLocked()
        if (readOnly) return@withLock false
        val next = change(mutableState.value)
        val target = file
        if (target != null) {
            val ok = withContext(Dispatchers.IO) { runCatching { writeAtomically(target, knownAddressesFileEncode(next)) }.isSuccess }
            if (!ok) return@withLock false
        }
        mutableState.value = next
        true
    }
}

/**
 * Where an accepted `lists.json` is kept between launches (SPEC §4.3): `files/QRLists`. Reads are
 * capped at the core's limit, so a larger saved file is never read.
 */
class ListsStore(context: Context, directory: File? = null) {
    private val folder = directory ?: File(context.filesDir, "QRLists")
    val file = File(folder, FILE_NAME)

    fun read(): ByteArray? {
        val size = file.takeIf { it.isFile }?.length() ?: return null
        if (size <= 0 || size > MAX_BYTES) return null
        return runCatching { AtomicFile(file).readFully() }.getOrNull()
    }

    fun write(bytes: ByteArray) = writeAtomically(file, bytes)

    fun remove() {
        AtomicFile(file).delete()
    }

    companion object {
        const val FILE_NAME = "lists.json"
        val MAX_BYTES: Long = listsMaxBytes()
    }
}

/** The app's own settings (SPEC §11). Keep history is on by default; off stops new saving only. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("qr-settings", Context.MODE_PRIVATE)
    private val mutableKeep = MutableStateFlow(prefs.getBoolean(KEEP_HISTORY, true))
    val keepHistory: StateFlow<Boolean> = mutableKeep.asStateFlow()

    fun setKeepHistory(value: Boolean) {
        prefs.edit { putBoolean(KEEP_HISTORY, value) }
        mutableKeep.value = value
    }

    companion object {
        const val KEEP_HISTORY = "keepHistory"
    }
}
