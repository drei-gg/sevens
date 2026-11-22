package gg.seven.bloom

import android.app.Application
import android.net.Uri
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

    fun exportReport(uri: Uri, text: String, kind: String) = run {
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { stream
                ->
                if (kind != "pdf") stream.write(text.toByteArray())
                else {
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
                            while (
                                line < layout.lineCount && layout.getLineBottom(line) - top <= 720
                            ) line++
                            if (line == first) line++
                            val page =
                                pdf.startPage(
                                    android.graphics.pdf.PdfDocument.PageInfo.Builder(
                                            595,
                                            842,
                                            pageNumber,
                                        )
                                        .create()
                                )
                            page.canvas.save()
                            page.canvas.clipRect(
                                48f,
                                48f,
                                547f,
                                (48 + layout.getLineTop(line) - top).toFloat(),
                            )
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
            }
        }
    }

    fun exportFile(uri: Uri, kind: String, password: String, contacts: Boolean, notes: Boolean) =
        run {
            val json = repository.read()
            val bytes =
                withContext(Dispatchers.Default) {
                    when (kind) {
                        "age" -> bloomSeal(json, password)
                        "csv" -> bloomCsv(json, contacts, notes).toByteArray()
                        else -> json.toByteArray()
                    }
                }
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write(bytes)
                }
            }
        }
}
