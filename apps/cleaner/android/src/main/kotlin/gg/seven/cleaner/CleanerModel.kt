package gg.seven.cleaner

import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.ContactMatch
import gg.seven.cleaner.core.MatchGroup
import gg.seven.cleaner.core.MatchKind
import gg.seven.cleaner.core.ReviewDecision
import gg.seven.cleaner.core.ReviewState
import gg.seven.cleaner.core.contactCandidates
import gg.seven.cleaner.core.matchingProposedRemoval
import gg.seven.cleaner.core.newReviewState
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A change Android lets an app make only after the person confirms it in a system dialog. */
class SystemRequest(val intent: PendingIntent, val onResult: (Boolean) -> Unit)

/**
 * Cleaner's state and actions (iOS CleanerModel). Every rule comes from the core; this keeps what
 * the person sees, runs one job at a time, and stores decisions before showing them.
 */
class CleanerModel(app: Application) : AndroidViewModel(app) {
    val store =
        SevenStore(
            app,
            BuildConfig.PERSONAL_UNLOCK,
            sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY),
            viewModelScope,
        )
    val repository = ReviewStore(File(app.filesDir, "Cleaner/review.json"))
    val photos = PhotosService(app)
    val contacts = ContactsService(app)
    val files = FilesService(app)

    val state = MutableStateFlow(newReviewState())
    val media = MutableStateFlow<List<CleanupItem>>(emptyList())
    val groups = MutableStateFlow<List<MatchGroup>>(emptyList())
    val folderScan = MutableStateFlow<FolderScan?>(null)
    val contactSnapshot = MutableStateFlow<ContactSnapshot?>(null)
    val contactMatches = MutableStateFlow<List<ContactMatch>>(emptyList())
    val limitedPhotos = MutableStateFlow(false)
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val progress = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    /** The error is the paid-feature one, so its dialog can offer the unlock. */
    val errorOffersUnlock = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val canCancel = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val candidate = MutableStateFlow<CompressionCandidate?>(null)
    val candidateSource = MutableStateFlow<String?>(null)
    val exportFile = MutableStateFlow<File?>(null)
    val systemRequest = MutableStateFlow<SystemRequest?>(null)

    private var job: Job? = null
    private var started = false
    private val cache = app.cacheDir

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                state.value = repository.read()
                ready.value = true
            } catch (failure: Exception) {
                showError(failure.cleanerMessage)
            }
        }
    }

    fun showError(message: String) {
        errorOffersUnlock.value = false
        error.value = message
    }

    fun run(title: String, paid: Boolean = true, cancellable: Boolean = true, operation: suspend () -> Unit) {
        if (!ready.value || busy.value) return
        if (paid && !store.paidFeaturesAvailable) {
            error.value = "Start the free trial or unlock to run new scans. Your saved review decisions and exports remain available."
            errorOffersUnlock.value = true
            return
        }
        busy.value = true
        canCancel.value = cancellable
        progress.value = title
        job =
            viewModelScope.launch {
                try {
                    operation()
                } catch (_: CancellationException) {
                    progress.value = "Cancelled"
                } catch (failure: Exception) {
                    showError(failure.cleanerMessage)
                } finally {
                    busy.value = false
                    canCancel.value = false
                    progress.value = ""
                    job = null
                }
            }
    }

    fun cancel() {
        if (!canCancel.value) return
        job?.cancel()
        progress.value = "Stopping after the current resource…"
    }

    fun scanPhotos() =
        run("Reading accessible media…") {
            val inventory = photos.inventory()
            media.value = inventory.items
            limitedPhotos.value = inventory.limited
            groups.value = emptyList()
            selected.value = emptySet()
        }

    fun analyze() =
        run("Reading original media bytes…") {
            val result = photos.analyze(media.value) { done, total -> progress.value = "Measured $done of $total" }
            media.value = result.items
            groups.value = result.groups
            state.value =
                repository.record(
                    "Compared ${result.items.size} media items; ${result.unavailable} unavailable or changed. Byte counts describe library files, not local space freed."
                )
        }

    fun findSimilar() =
        run("Comparing nearby photos…") {
            val result = photos.similar(media.value)
            groups.update { current -> current.filter { it.kind != MatchKind.SIMILAR } + result }
        }

    fun decide(ids: Set<String>, decision: ReviewDecision) {
        if (!ready.value || ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val revisions = media.value.filter { it.id in ids }.associate { it.id to it.revision }
                state.value = repository.decide(ids.associateWith { decision }, revisions)
            } catch (failure: Exception) {
                showError(failure.cleanerMessage)
            }
        }
    }

    fun toggle(id: String) = selected.update { if (id in it) it - id else it + id }

    fun smartSelect() {
        selected.value = matchingProposedRemoval(groups.value, media.value, state.value.decisions).toSet()
    }

    /**
     * Checks the reviewed items against the library, then asks the system to move them to the
     * trash. Only after the person confirms there, and the items are gone, is it recorded.
     */
    fun deleteReviewed(ids: Set<String>) =
        run("Applying reviewed deletion…", cancellable = false) {
            val intent = photos.trashRequest(ids, state.value)
            systemRequest.value =
                SystemRequest(intent) { confirmed ->
                    if (confirmed)
                        run("Applying reviewed deletion…", cancellable = false) {
                            val gone = ids - photos.remaining(ids)
                            if (gone.isEmpty()) return@run
                            state.value =
                                repository.record(
                                    "Requested deletion for ${gone.size} reviewed items. They wait in the trash for 30 days and keep using storage until then."
                                )
                            media.update { list -> list.filterNot { it.id in gone } }
                            groups.value = emptyList()
                            selected.update { it - gone }
                        }
                }
        }

    fun setFavorite(item: CleanupItem) =
        run("Updating favorite…", cancellable = false) {
            val intent = photos.favoriteRequest(item.id, !item.favorite)
            systemRequest.value =
                SystemRequest(intent) { confirmed ->
                    if (confirmed)
                        viewModelScope.launch {
                            val now = photos.isFavorite(item.id)
                            media.update { list -> list.map { if (it.id == item.id) it.copy(favorite = now) else it } }
                        }
                }
        }

    fun compress(item: CleanupItem) =
        run("Preparing an original and checking a compressed candidate…") {
            val sourceKey = item.id + "|" + item.revision
            val processed = state.value.processedSources
            if (sourceKey in processed || "output:" + item.id in processed)
                throw blocked("A compressed copy was already saved for this source revision.")
            val directory = File(cache, "CleanerCandidate-" + UUID.randomUUID())
            try {
                val original = photos.originalStill(item, directory)
                candidate.value = ImageCompression.makeCandidate(original, directory)
                candidateSource.value = item.id
            } catch (failure: Throwable) {
                directory.deleteRecursively()
                throw failure
            }
        }

    fun discardCandidate() {
        candidate.value?.output?.parentFile?.deleteRecursively()
        candidate.value = null
        candidateSource.value = null
    }

    fun saveCandidate() =
        run("Saving a copy; keeping the original…", cancellable = false) {
            val candidate = candidate.value ?: return@run
            val item = media.value.firstOrNull { it.id == candidateSource.value } ?: return@run
            val saved = photos.saveCopy(candidate, item)
            state.value = repository.record("Verified saved copy.", processedSource = "output:$saved")
            state.value =
                repository.record("Saved a compressed copy of ${item.name}. Original retained.", processedSource = item.id + "|" + item.revision)
            discardCandidate()
        }

    fun scanContacts(region: String?) =
        run("Reading contact records and verifying archives…") {
            val snapshot = contacts.scan(region)
            contactSnapshot.value = snapshot
            contactMatches.value = contactCandidates(snapshot.records)
        }

    fun exportContacts() =
        run("Writing and checking the contact archive…", paid = false) {
            val snapshot = contactSnapshot.value ?: return@run
            val file = File(cache, "exports/Contacts-${UUID.randomUUID()}.cleanercontacts")
            contacts.export(snapshot, file)
            exportFile.value = file
            state.value =
                repository.record(
                    "Exported ${snapshot.records.size} accessible contact records. Notes and account relationships are not included; originals retained."
                )
        }

    fun scanFolder(tree: Uri) = run("Reading the selected folder…") { folderScan.value = files.scan(tree, hash = true) }

    /** For tests and the screen tour: sample data; no library, contacts or files are touched. */
    internal fun seed(
        media: List<CleanupItem>,
        groups: List<MatchGroup>,
        state: ReviewState,
        folder: FolderScan?,
        contacts: ContactSnapshot?,
    ) {
        this.media.value = media
        this.groups.value = groups
        this.state.value = state
        folderScan.value = folder
        contactSnapshot.value = contacts
        contactMatches.value = contacts?.let { contactCandidates(it.records) }.orEmpty()
        ready.value = true
    }

    override fun onCleared() {
        discardCandidate()
        store.close()
    }
}
