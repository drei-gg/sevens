package gg.seven.qr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import gg.seven.qr.core.Analysis
import gg.seven.qr.core.HistoryItem
import gg.seven.qr.core.HistoryKind
import gg.seven.qr.core.KnownAddresses
import gg.seven.qr.core.ScannedCode
import gg.seven.qr.core.historyItemFromAnalysis
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one object every screen reads (iOS AppModel). Gating, in one place: only *saving new items*
 * to History needs the trial or the unlock. Reading, search, notes, export and delete work in every
 * state, so what was saved during the trial stays readable and exportable (SPEC §13).
 */
class QrModel(app: Application) : AndroidViewModel(app) {
    val store =
        SevenStore(
            app,
            BuildConfig.PERSONAL_UNLOCK,
            sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY),
            viewModelScope,
        )
    val settings = AppSettings(app)
    val history = HistoryStore(historyDirectory(app), viewModelScope)
    private val knownStore = KnownAddressStore(historyDirectory(app))

    val historyState: StateFlow<HistoryState> = history.state
    /** The addresses the person said they know (SPEC §4.4). */
    val knownAddresses: StateFlow<KnownAddresses> = knownStore.addresses

    val nav = QrNav()

    /** A code handed in at launch (a debug intent extra): the scan screen shows it. */
    val pendingCode = MutableStateFlow<ScannedCode?>(null)

    init {
        QrLists.start(app)
        viewModelScope.launch {
            knownStore.load()
            history.load()
        }
    }

    // MARK: analysis

    /** Off the main thread, with the addresses the person knows applied. */
    suspend fun analysis(code: ScannedCode): Analysis {
        val known = knownAddresses.value
        return withContext(Dispatchers.Default) { analyze(code, known) }
    }

    // MARK: addresses you know (never gated: free forever)

    suspend fun knowAddress(domain: String): Boolean = knownStore.add(domain)

    suspend fun forgetAddress(domain: String): Boolean = knownStore.remove(domain)

    suspend fun forgetAllAddresses(): Boolean = knownStore.removeAll()

    // MARK: History: saving (gated)

    /** Paid features are not available: nothing new is saved. */
    val historyIsLocked
        get() = !store.paidFeaturesAvailable

    /** Keep history on AND (trial or unlocked). */
    val canSaveHistory
        get() = settings.keepHistory.value && store.paidFeaturesAvailable

    /** Saves a scanned or made code; null when nothing was saved (off, locked, or read-only file). */
    suspend fun record(analysis: Analysis, kind: HistoryKind): HistoryItem? {
        if (!canSaveHistory) return null
        val item = historyItemFromAnalysis(analysis, kind, Instant.now())
        return if (history.add(item)) item else null
    }

    /** Analyses [code] off the main thread and saves it. Used for made codes. */
    suspend fun record(code: ScannedCode, kind: HistoryKind): HistoryItem? {
        if (!canSaveHistory) return null
        return record(analysis(code), kind)
    }

    /** Saves a made code in the model's scope, so leaving Make doesn't cancel the write. */
    fun recordMade(code: ScannedCode) {
        viewModelScope.launch { record(code, HistoryKind.MADE) }
    }

    // MARK: History: never gated

    suspend fun searchHistory(query: String) = history.search(getApplication<Application>().resources, query)

    suspend fun setNote(note: String?, id: UUID) = history.updateNote(note, id)

    suspend fun deleteHistory(ids: Set<UUID>) = history.delete(ids)

    suspend fun clearHistory() = history.clear()

    suspend fun exportHistoryJson(): ByteArray = history.exportJson(knownAddresses.value)

    suspend fun exportHistoryCsv(): String = history.exportCsv(knownAddresses.value)

    /** The app went to the background: write any pending History change now. */
    fun flush() {
        viewModelScope.launch { history.flush() }
    }

    override fun onCleared() = store.close()
}
