package gg.seven.bloom

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.bloom.core.*
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BloomModel(app: Application) : AndroidViewModel(app) {
    val store =
        SevenStore(
            app,
            BuildConfig.PERSONAL_UNLOCK,
            sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY),
            viewModelScope,
        )

    override fun onCleared() {
        store.close()
        super.onCleared()
    }

    val repository = (app as BloomApplication).repository
    internal val appLock = JournalLock.stored(app)
    val reminderStatus = MutableStateFlow("Checking reminder delivery…")
    val openCare = MutableStateFlow(false)
    val archive = MutableStateFlow<String?>(null)
    val dashboard = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val preview = MutableStateFlow<String?>(null)
    val healthPreview = MutableStateFlow<String?>(null)
    val healthNote =
        MutableStateFlow(
            "Nothing is shared automatically. Choose a direction to request its permission."
        )

    internal fun protectJournal() {
        appLock.protect()
        if (appLock.enabled.value) {
            healthPreview.value = null
            preview.value = null
            report.value = null
            error.value = null
        }
    }

    private val reloadMutex = Mutex()

    init {
        BloomReminders.maintain(app)
        run { reload() }
        viewModelScope.launch {
            repository.changes.drop(1).collect {
                try { reload() } catch (cancel: CancellationException) { throw cancel }
                catch (failure: kotlin.Exception) { error.value = failure.sentence }
            }
        }
    }

    private suspend fun reload() = reloadMutex.withLock {
        archive.value = repository.read()
        dashboard.value = repository.dashboard()
        try {
            BloomReminders.reconcile(getApplication(), deliver = true)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            /* Records are saved independently of notification permission. */
        }
        BloomGlanceWidget.refresh(getApplication())
        reminderStatus.value = BloomReminders.status(getApplication())
    }

    fun run(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: kotlin.Exception) {
                error.value = failure.sentence
            } finally {
                busy.value = false
            }
        }
    }

    fun save(op: String, record: JSONObject, revision: Long, done: () -> Unit) = run {
        repository.change(command(op, record, revision), store.paidFeaturesAvailable)
        reload()
        done()
    }

    fun delete(op: String, id: String) = run {
        val revision = JSONObject(repository.read()).getLong("revision")
        repository.change(command(op, null, revision, id), store.paidFeaturesAvailable)
        reload()
    }

    fun refresh() {
        store.refresh()
        if (archive.value != null) run { reload() }
    }

    fun erase() = run {
        repository.erase()
        report.value = null
        withContext(Dispatchers.IO) {
            java.io.File(getApplication<Application>().cacheDir, "reports").deleteRecursively()
        }
        BloomReminders.clear(getApplication())
        reload()
    }

    fun importFile(uri: Uri, password: String?) = run {
        val bytes =
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
                    it.readBytesBounded(17_000_000)
                }
            }
        preview.value = repository.inspect(bytes, password)
    }

    fun confirmImport() = run {
        preview.value?.let { repository.merge(it) }
        preview.value = null
        reload()
    }

    fun readHealth() = run {
        if (appLock.locked.value) return@run
        healthPreview.value = null
        val samples = readHealthFlow(getApplication())
        if (appLock.locked.value) return@run
        val reviewed = bloomHealthReview(repository.read(), samples)
        val count = JSONObject(reviewed).rows("days").size
        healthPreview.value = reviewed.takeIf { count > 0 }
        healthNote.value =
            if (count == 0) "No new readable flow days. Existing journal dates were skipped."
            else
                "Review $count new days. Multiple flow samples on one date use the highest recorded flow."
    }

    fun importHealth() = run {
        val reviewed = healthPreview.value ?: return@run
        repository.importHealth(reviewed)
        reload()
        healthPreview.value = null
        healthNote.value =
            "Reviewed flow days imported. Any dates added to your journal during review were kept."
    }

    fun shareHealth() = run {
        if (appLock.locked.value) return@run
        val count = writeHealthFlow(getApplication(), repository.read())
        healthNote.value =
            if (count == 0) "No light, medium or heavy flow entries to share."
            else
                "Shared $count flow entries. Later journal deletions do not delete Health Connect copies; manage those in Health Connect."
    }

    /** The reviewed report lives here, never in a saved-state Bundle, and disappears on lock. */
    val report = MutableStateFlow<String?>(null)

    fun buildReport(from: String, to: String, contacts: Boolean, notes: Boolean) = run {
        val archive = repository.read()
        report.value =
            withContext(Dispatchers.Default) { bloomReport(archive, from, to, contacts, notes) }
    }

    fun exportReport(uri: Uri, kind: String) = run {
        val text = report.value
        if (text == null) {
            deleteDocument(uri)
            error.value = "Preview the report again, then save it."
            return@run
        }
        writeDocument(uri) { stream -> writeReport(stream, text, kind) }
    }

    /** Shares the reviewed report: text directly, a PDF through this app's file provider. */
    fun shareReport(kind: String, launch: (Intent) -> Unit) = run {
        val text = report.value ?: return@run
        val send =
            if (kind == "pdf") {
                val file =
                    withContext(Dispatchers.IO) {
                        val folder = java.io.File(getApplication<Application>().cacheDir, "reports")
                        folder.deleteRecursively()
                        folder.mkdirs()
                        java.io.File(folder, "cycle-report.pdf").also { file ->
                            file.outputStream().use { writeReport(it, text, "pdf") }
                        }
                    }
                val uri =
                    FileProvider.getUriForFile(
                        getApplication(),
                        "${getApplication<Application>().packageName}.files",
                        file,
                    )
                Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = android.content.ClipData.newRawUri(null, uri) }
            } else
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, "Share report"))
    }

    /** Doses scheduled on a calendar day, from the Rust dashboard evaluated at that day's noon. */
    suspend fun scheduledOn(day: String): List<JSONObject> {
        val noon =
            java.time.LocalDate.parse(day)
                .atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault())
                .toEpochSecond()
        return JSONObject(repository.dashboard(noon)).rows("today_doses")
    }

    /**
     * Writes a document the system picker has just created. A failure removes the partial file, so
     * a rejected passphrase or full disk never leaves an empty backup behind.
     */
    private suspend fun writeDocument(uri: Uri, write: (java.io.OutputStream) -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use(write)
            }
        } catch (failure: Exception) {
            deleteDocument(uri)
            throw failure
        }
    }

    private suspend fun deleteDocument(uri: Uri) {
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            try {
                DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri)
            } catch (_: Exception) {
                /* The provider may not support deletion; the write error is still reported. */
            }
        }
    }

    fun exportFile(uri: Uri, kind: String, password: String, contacts: Boolean, notes: Boolean) =
        run {
            val bytes =
                try {
                    val json = repository.read()
                    withContext(Dispatchers.Default) {
                        when (kind) {
                            "age" -> bloomSeal(json, password)
                            "csv" -> bloomCsv(json, contacts, notes).toByteArray()
                            else -> json.toByteArray()
                        }
                    }
                } catch (failure: Exception) {
                    deleteDocument(uri)
                    throw failure
                }
            writeDocument(uri) { it.write(bytes) }
        }
}

/** Plain text, or an A4 PDF paginated from the same reviewed text. */
private fun writeReport(stream: java.io.OutputStream, text: String, kind: String) {
    if (kind != "pdf") {
        stream.write(text.toByteArray())
        return
    }
    val paint =
        android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = android.graphics.Color.BLACK
        }
    val layout =
        android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, 499)
            .setLineSpacing(3f, 1f)
            .build()
    val pdf = android.graphics.pdf.PdfDocument()
    try {
        var line = 0
        var pageNumber = 1
        while (line < layout.lineCount) {
            val first = line
            val top = layout.getLineTop(first)
            while (line < layout.lineCount && layout.getLineBottom(line) - top <= 720) line++
            if (line == first) line++
            val page =
                pdf.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                )
            page.canvas.save()
            page.canvas.clipRect(48f, 48f, 547f, (48 + layout.getLineTop(line) - top).toFloat())
            page.canvas.translate(48f, (48 - top).toFloat())
            layout.draw(page.canvas)
            page.canvas.restore()
            page.canvas.drawText("Page $pageNumber", 48f, 810f, paint)
            pdf.finishPage(page)
            pageNumber++
        }
        pdf.writeTo(stream)
    } finally {
        pdf.close()
    }
}
