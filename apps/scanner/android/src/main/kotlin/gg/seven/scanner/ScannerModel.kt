package gg.seven.scanner

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import gg.seven.scanner.core.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sentence a person sees for a failure: the core's errors in the iOS wording. */
fun describe(error: Throwable): String =
    when (error) {
        is ScanException.InvalidDocument -> "Something in this document or file isn’t valid, so nothing was changed."
        is ScanException.TooLarge -> {
            val limits = scanLimits()
            "This is more than one document can hold: up to ${limits.maxPages} pages and ${limits.maxDocumentImageBytes / 1_000_000} MB of images."
        }
        is ScanException.MissingPage -> "A page’s image is missing, so nothing was changed."
        is ScanException.Unreadable -> "This file couldn’t be read. It may be damaged or from a newer version. Nothing was changed."
        else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "Something went wrong. Nothing was changed."
    }

const val NEEDS_ACCESS = "Start a trial or unlock to scan or import pages. Your saved documents remain available."

class ScannerModel(app: Application) : AndroidViewModel(app) {
    val store =
        SevenStore(
            app,
            BuildConfig.PERSONAL_UNLOCK,
            sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY),
            viewModelScope,
        )
    val repository = DocumentStore(File(app.filesDir, LIBRARY))
    private val prefs = app.getSharedPreferences("scanner", Context.MODE_PRIVATE)

    private val _documents = MutableStateFlow<List<ScanDocument>>(emptyList())
    val documents: StateFlow<List<ScanDocument>> = _documents.asStateFlow()
    private val _deleted = MutableStateFlow<List<ScanDocument>>(emptyList())
    val deleted: StateFlow<List<ScanDocument>> = _deleted.asStateFlow()
    val unreadable = MutableStateFlow(0)
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val loaded = MutableStateFlow(false)
    val appearance = MutableStateFlow(prefs.getString(APPEARANCE, "system") ?: "system")
    val order = MutableStateFlow(prefs.getString(ORDER, null)?.let(::libraryOrderFromRawValue) ?: LibraryOrder.NEWEST)

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        withContext(Dispatchers.IO) { Processing.cleanOldExports(getApplication()) }
        runCatching { repository.io { cleanUp() } }
        reload()
        loaded.value = true
    }

    fun setAppearance(value: String) {
        appearance.value = value
        prefs.edit { putString(APPEARANCE, value) }
    }

    fun setOrder(value: LibraryOrder) {
        order.value = value
        prefs.edit { putString(ORDER, libraryOrderRawValue(value)) }
    }

    suspend fun reload() {
        try {
            val (active, gone) = repository.io { readLibrary(false) to readLibrary(true) }
            _documents.value = active.documents
            _deleted.value = gone.documents
            unreadable.value = (active.unreadableCount + gone.unreadableCount).toInt()
        } catch (e: Exception) {
            error.value = describe(e)
        }
    }

    /** Camera pages (or ML Kit's), normalized off the main thread, then saved and recognized. */
    fun capture(sources: List<Uri>, appendTo: UUID? = null, cleanUp: () -> Unit = {}) =
        viewModelScope.launch {
            try {
                addPages("Preparing pages…", appendTo) { staging ->
                    val limits = scanLimits()
                    if (sources.isEmpty() || sources.size > limits.maxPages) throw ScanException.TooLarge()
                    val resolver = getApplication<Application>().contentResolver
                    var total = 0L
                    sources.map { uri ->
                        val bitmap = Processing.decode({ resolver.openInputStream(uri) ?: throw ScanException.MissingPage() }, pageProcessing().normalizedMaxPixels.toInt())
                        val out = File(staging, "${UUID.randomUUID()}.jpg")
                        Processing.normalize(bitmap, out)
                        bitmap.recycle()
                        total += out.length()
                        if (total > limits.maxDocumentImageBytes) throw ScanException.TooLarge()
                        out
                    }
                }
            } finally {
                withContext(Dispatchers.IO) { runCatching(cleanUp) }
            }
        }

    /** Images and PDFs from the system picker. */
    fun importFiles(uris: List<Uri>, appendTo: UUID? = null) =
        viewModelScope.launch { addPages("Importing pages…", appendTo) { staging -> Processing.importFiles(getApplication(), uris, staging) } }

    private suspend fun addPages(message: String, appendTo: UUID?, prepare: suspend (File) -> List<File>) {
        if (busy.value) return
        // Adding pages is new scanning, so it needs access like a new document. Editing existing pages stays free.
        if (!store.paidFeaturesAvailable) {
            error.value = NEEDS_ACCESS
            return
        }
        busy.value = true
        progress.value = message
        val staging = File(getApplication<Application>().cacheDir, "ScannerStaging/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val pages = withContext(Dispatchers.Default) { prepare(staging) }
            progress.value = "Saving document…"
            val saved =
                repository.io {
                    if (appendTo != null) append(appendTo, pages)
                    else create("Scan " + titleStamp(System.currentTimeMillis()), pages, List(pages.size) { "" })
                }
            reload()
            progress.value = "Scan saved. Recognizing text…"
            recognizeNow(saved, onlyEmpty = true)
        } catch (e: Exception) {
            error.value = describe(e)
        } finally {
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
            busy.value = false
            progress.value = ""
        }
    }

    fun recognize(document: ScanDocument, onlyEmpty: Boolean = false, done: () -> Unit = {}) =
        viewModelScope.launch {
            recognizeNow(document, onlyEmpty)
            done()
        }

    /** OCR the pages (or only those without text) and merge by page ID without overwriting newer edits. */
    suspend fun recognizeNow(document: ScanDocument, onlyEmpty: Boolean = false) {
        try {
            val pages = if (onlyEmpty) document.pages.filter { it.text.isEmpty() } else document.pages
            if (pages.isEmpty()) return
            val selection = document.copy(pages = pages)
            val files = repository.io { pageFiles(selection) }
            val text =
                withContext(Dispatchers.Default) {
                    Recognizer.open(getApplication()).use { ocr ->
                        pages.zip(files).associate { (page, file) ->
                            val bitmap = Processing.rendered(file, page.settings)
                            page.id to ocr.text(bitmap).also { bitmap.recycle() }
                        }
                    }
                }
            repository.io { fillRecognizedText(document.id, text, pages.associate { it.id to it.settings }, overwrite = !onlyEmpty) }
            reload()
        } catch (_: Throwable) {
            if (_documents.value.any { it.id == document.id })
                error.value = "Your scan is saved. Text recognition could not finish. You can retry from the document."
        }
    }

    /** Stars or unstars a document without touching its name or notes. */
    suspend fun setFavorite(document: ScanDocument, favorite: Boolean): ScanDocument? =
        attempt { repository.io { updateMetadata(document.id, document.title, document.note.orEmpty(), favorite) } }

    suspend fun saveDetails(document: ScanDocument, title: String, note: String): ScanDocument? =
        attempt { repository.io { updateMetadata(document.id, title, note, document.favorite ?: false) } }

    /** Reorders or removes pages; refuses an order made on a stale copy (an append in between). */
    suspend fun setPageOrder(document: ScanDocument, ids: List<UUID>): ScanDocument? {
        if (ids.isEmpty()) {
            error.value = "Keep at least one page, or delete the document."
            return null
        }
        return attempt { repository.io { setPageOrder(document.id, ids, document.pages.map { it.id }) } }
    }

    /** Saves a page's adjustments, then recognizes that page again. `null` on success, else the failure. */
    suspend fun updatePage(document: ScanDocument, pageId: UUID, settings: PageSettings): String? =
        try {
            val updated = repository.io { updatePage(document.id, pageId, settings) }
            reload()
            viewModelScope.launch { recognizeNow(updated.copy(pages = updated.pages.filter { it.id == pageId })) }
            null
        } catch (e: Exception) {
            describe(e)
        }

    suspend fun delete(document: ScanDocument): Boolean = attempt { repository.io { delete(document) } } != null

    suspend fun restore(document: ScanDocument) {
        attempt { repository.io { restore(document) } }
    }

    suspend fun permanentlyDelete(document: ScanDocument) {
        attempt { repository.io { permanentlyDelete(document) } }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block().also { reload() }
        } catch (e: Exception) {
            error.value = describe(e)
            null
        }

    override fun onCleared() = store.close()

    companion object {
        const val LIBRARY = "Scanner"
        private const val APPEARANCE = "scanner.appearance"
        private const val ORDER = "scanner.order"

        /** "Nov 16, 2026, 4:05 PM": the new document's name, as iOS stamps it. */
        fun titleStamp(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}
