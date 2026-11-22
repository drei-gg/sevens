package gg.seven.cleaner

import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.ContactMatch
import gg.seven.cleaner.core.ContactRecord
import gg.seven.cleaner.core.ImageSizeProfile
import gg.seven.cleaner.core.MatchGroup
import gg.seven.cleaner.core.MatchKind
import gg.seven.cleaner.core.ReviewDecision
import gg.seven.cleaner.core.ReviewState
import gg.seven.cleaner.core.compressionReviewPlan
import gg.seven.cleaner.core.compressionSourceKey
import gg.seven.cleaner.core.contactCandidates
import gg.seven.cleaner.core.contactPackageDecode
import gg.seven.cleaner.core.matchingProposedRemoval
import gg.seven.cleaner.core.newReviewState
import gg.seven.cleaner.core.removalHeldMessage
import gg.seven.cleaner.core.removalReadyIds
import gg.seven.cleaner.core.scanHasMeasurement
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The latest contact archive the person exported, kept in the app's no-backup storage so it can be
 * shared again after the trial or a relaunch. [records] is null when the file cannot be read.
 * It was read back and checked when written, so it is the real backup state for merge review.
 */
data class SavedContactArchive(val records: List<ContactRecord>?, val saved: Instant) {
    /** Whether this archive holds exactly these record versions (a verified backup of them). */
    fun covers(vararg wanted: ContactRecord): Boolean =
        records != null &&
            wanted.all { want ->
                records.any {
                    it.id == want.id &&
                        it.container == want.container &&
                        it.name == want.name &&
                        it.fields == want.fields
                }
            }
}

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
    val measurements = MeasurementCache(app)
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
    val comparisonFraction = MutableStateFlow<Float?>(null)
    val savedMeasurements = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    /** The error is the paid-feature one, so its dialog can offer the unlock. */
    val errorOffersUnlock = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val canCancel = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val candidate = MutableStateFlow<CompressionCandidate?>(null)
    val candidateSource = MutableStateFlow<String?>(null)
    val candidateItem = MutableStateFlow<CleanupItem?>(null)
    val createdCopyID = MutableStateFlow<String?>(null)
    val compressionBatch = MutableStateFlow<CompressionBatch?>(null)
    val videoReview = MutableStateFlow<VideoReview?>(null)
    private val sessionCopyKeys = java.util.concurrent.CopyOnWriteArraySet<String>()
    val exportFile = MutableStateFlow<File?>(null)
    val savedContactArchive = MutableStateFlow<SavedContactArchive?>(null)
    val systemRequest = MutableStateFlow<SystemRequest?>(null)

    private var job: Job? = null
    private var started = false
    private val cache = app.cacheDir
    /** Local only: excluded from backups and device transfer, deleted from Contacts settings. */
    private val savedArchiveFile =
        File(app.noBackupFilesDir, "Cleaner/contact-archive.cleanercontacts")

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                state.value = repository.read()
                ready.value = true
                savedContactArchive.value = readSavedArchive()
                savedMeasurements.value = measurements.count()
            } catch (failure: Exception) {
                showError(failure.cleanerMessage)
            }
        }
    }

    fun showError(message: String) {
        errorOffersUnlock.value = false
        error.value = message
    }

    fun run(
        title: String,
        paid: Boolean = true,
        cancellable: Boolean = true,
        operation: suspend () -> Unit,
    ) {
        if (!ready.value || busy.value) return
        if (paid && !store.paidFeaturesAvailable) {
            error.value =
                "Start the free trial or unlock to run new scans. Your saved review decisions and exports remain available."
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
                    withContext(NonCancellable) {
                        runCatching { savedMeasurements.value = measurements.count() }
                            .onFailure { showError(it.cleanerMessage) }
                    }
                    comparisonFraction.value = null
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
            media.value = measurements.restore(inventory.items)
            savedMeasurements.value = media.value.count { scanHasMeasurement(it) }
            limitedPhotos.value = inventory.limited
            groups.value = emptyList()
            selected.value = emptySet()
        }

    fun analyze() =
        run("Reading original media bytes…") {
            // Access and revisions may have changed while this screen was open.
            val inventory = photos.inventory()
            val current = measurements.restore(inventory.items)
            media.value = current
            limitedPhotos.value = inventory.limited
            groups.value = emptyList()
            selected.value = emptySet()
            val resumed = current.count { scanHasMeasurement(it) }
            savedMeasurements.value = resumed
            comparisonFraction.value = 0f
            val result =
                photos.analyze(current, checkpoint = measurements::checkpoint) { done, total ->
                    progress.value = "Checked $done of $total · $resumed already measured"
                    comparisonFraction.value = if (total == 0) 1f else done.toFloat() / total
                }
            media.value = result.items
            groups.value = result.groups
            state.value =
                repository.record(
                    "Compared ${result.items.size} media items; ${result.unavailable} unavailable or changed. Byte counts describe library files, not local space freed."
                )
        }

    fun clearScanProgress() =
        run("Clearing saved measurements…", paid = false, cancellable = false) {
            measurements.clear()
            savedMeasurements.value = 0
            media.update { items -> items.map { it.copy(fingerprint = null, bytes = null) } }
            groups.value = emptyList()
            selected.value = emptySet()
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
                val revisions =
                    media.value.filter { it.id in ids }.associate { it.id to it.revision }
                state.value = repository.decide(ids.associateWith { decision }, revisions)
            } catch (failure: Exception) {
                showError(failure.cleanerMessage)
            }
        }
    }

    fun toggle(id: String) = selected.update { if (id in it) it - id else it + id }

    fun smartSelect() {
        selected.value =
            matchingProposedRemoval(groups.value, media.value, state.value.decisions).toSet()
    }

    /**
     * Checks the reviewed items against the library, then asks the system to move them to the
     * trash. Only after the person confirms there, and the items are gone, is it recorded.
     */
    fun deleteReviewed(ids: Set<String>) =
        run("Applying reviewed deletion…", cancellable = false) {
            // The core names each item that changed, became a favorite or is gone; only the
            // ready ones are offered to the system, and the rest are marked for review again.
            val check = photos.removalCheck(ids, state.value)
            replaceItems(ids, check.current)
            val ready = removalReadyIds(check.outcomes).toSet()
            val held = removalHeldMessage(check.outcomes)
            if (ready.isEmpty()) throw blocked(held ?: "Nothing selected.")
            val intent = photos.trashRequest(ready, state.value)
            systemRequest.value =
                SystemRequest(intent) { confirmed ->
                    if (confirmed)
                        run("Applying reviewed deletion…", cancellable = false) {
                            val gone = ready - photos.remaining(ready)
                            if (gone.isNotEmpty()) {
                                state.value =
                                    repository.record(
                                        "Requested deletion for ${gone.size} reviewed items. They wait in the trash for 30 days and keep using storage until then."
                                    )
                                media.update { list -> list.filterNot { it.id in gone } }
                                groups.value = emptyList()
                                selected.update { it - gone }
                            }
                            held?.let(::showError)
                        }
                }
        }

    /**
     * Replaces [ids] in the media list with the library's current items: an unchanged revision
     * keeps its measurement, a changed one takes the fresh item, and a missing one leaves the list.
     */
    private fun replaceItems(ids: Set<String>, current: List<CleanupItem>) {
        val fresh = current.associateBy { it.id }
        media.update { list ->
            list.mapNotNull { item ->
                if (item.id !in ids) item
                else
                    fresh[item.id]?.let { now ->
                        if (now.revision == item.revision) item.copy(favorite = now.favorite)
                        else now
                    }
            }
        }
    }

    private suspend fun refreshItems(ids: Set<String>) {
        if (ids.isNotEmpty()) replaceItems(ids, photos.currentItems(ids))
    }

    /** Pending removal opens against the library as it is now, so changed items show as such. */
    fun refreshPending() {
        if (!ready.value || busy.value) return
        val ids =
            media.value
                .filter { state.value.decisions[it.id] == ReviewDecision.REMOVE }
                .map { it.id }
        viewModelScope.launch { runCatching { refreshItems(ids.toSet()) } }
    }

    /**
     * Adds the selected photos and videos to a folder album: moved there after the system's write
     * confirmation (Android 11+), or copied there with the originals left in place (Android 10+).
     */
    fun addToAlbum(ids: Set<String>, title: String, move: Boolean) =
        run(
            if (move) "Checking the selected items…" else "Copying into the album…",
            cancellable = false,
        ) {
            val items = media.value.filter { it.id in ids }
            if (items.size != ids.size || items.isEmpty())
                throw blocked("A selected item is no longer available. Read the library again.")
            if (!move) {
                finishAlbum(photos.copyToAlbum(items, title), ids, title, moved = false)
                return@run
            }
            val intent = photos.moveRequest(items, title)
            systemRequest.value =
                SystemRequest(intent) { confirmed ->
                    if (confirmed)
                        run("Moving into the album…", cancellable = false) {
                            finishAlbum(photos.moveToAlbum(items, title), ids, title, moved = true)
                        }
                }
        }

    private suspend fun finishAlbum(
        result: AlbumResult,
        ids: Set<String>,
        title: String,
        moved: Boolean,
    ) {
        // A moved file has a new revision; decisions made before the move need review again.
        refreshItems(ids)
        val name = title.trim()
        state.value =
            repository.record(
                if (moved) "Moved ${result.done.size} of ${ids.size} items into the album “$name”."
                else
                    "Copied ${result.done.size} of ${ids.size} items into the album “$name”. Originals stay where they were."
            )
        if (result.failed.isNotEmpty())
            showError(
                "${result.failed.size} of ${ids.size} items could not be ${if (moved) "moved" else "copied"}. They stay where they were."
            )
    }

    fun setFavorite(item: CleanupItem) =
        run("Updating favorite…", cancellable = false) {
            val intent = photos.favoriteRequest(item.id, !item.favorite)
            systemRequest.value =
                SystemRequest(intent) { confirmed ->
                    if (confirmed)
                        viewModelScope.launch {
                            // A favorite change gives the item a new provider revision; take it,
                            // so later copies and decisions bind to what the library now has.
                            runCatching { refreshItems(setOf(item.id)) }
                                .onFailure { showError(it.cleanerMessage) }
                        }
                }
        }

    private suspend fun prepareCandidate(item: CleanupItem) {
        val persisted = repository.read()
        compressionReviewPlan(
            listOf(item.id),
            listOf(item),
            persisted.copy(processedSources = persisted.processedSources + sessionCopyKeys),
        )
        val directory = File(cache, "CleanerCandidate-" + UUID.randomUUID())
        try {
            val original = photos.originalStill(item, directory)
            candidate.value = ImageCompression.makeCandidate(original, directory)
            candidateSource.value = item.id
            candidateItem.value = item
            createdCopyID.value = null
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    fun compress(item: CleanupItem) =
        run("Preparing an original and checking a compressed candidate…") {
            if (compressionBatch.value != null || candidate.value != null)
                throw blocked("Finish the current review first.")
            prepareCandidate(item)
        }

    fun openVideo(item: CleanupItem) =
        run("Reading and checking the original video…") {
            if (
                videoReview.value != null ||
                    candidate.value != null ||
                    compressionBatch.value != null
            )
                throw blocked("Finish the current review first.")
            val persisted = repository.read()
            gg.seven.cleaner.core.checkVideoCopySource(
                item,
                persisted.copy(processedSources = persisted.processedSources + sessionCopyKeys),
            )
            val directory = File(cache, "video-${UUID.randomUUID()}")
            try {
                val source = photos.originalVideo(item, directory)
                val inspection =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        VideoCompression.inspect(source)
                    }
                gg.seven.cleaner.core.videoCopyPlan(
                    inspection.facts,
                    0u,
                    inspection.facts.durationMs,
                )
                videoReview.value = VideoReview(item, source, inspection)
                error.value = null
            } catch (failure: Throwable) {
                directory.deleteRecursively()
                throw failure
            }
        }

    fun prepareVideo() =
        run("Preparing and checking the selected video range…") {
            val review = videoReview.value ?: return@run
            if (review.createdID != null) throw blocked("Finish verifying the existing copy first.")
            val plan =
                review.plan() ?: throw blocked("Enter a valid start and end inside the video.")
            error.value = null
            val next =
                VideoCompression.makeCandidate(
                    getApplication(),
                    review.source,
                    review.source.parentFile!!,
                    plan.startMs,
                    plan.endMs,
                )
            review.candidate?.output?.delete()
            review.candidate = next
        }

    fun closeVideo() {
        if (busy.value) return
        videoReview.value?.source?.parentFile?.deleteRecursively()
        videoReview.value = null
        error.value = null
    }

    fun saveVideo() =
        run("Verifying and recording the video copy…", cancellable = false) {
            val review = videoReview.value ?: return@run
            val preview = review.candidate ?: return@run
            if (review.plan() != preview.plan || preview.issues.isNotEmpty())
                throw blocked("Prepare and inspect the current range before saving.")
            error.value = null
            val saved =
                review.createdID?.also { photos.verifySavedFile(preview.output, it) }
                    ?: photos.saveVideoCopy(preview, review.item) { id ->
                        review.createdID = id
                        sessionCopyKeys.add(compressionSourceKey(review.item))
                        sessionCopyKeys.add("output:$id")
                    }
            try {
                state.value = repository.recordCopy(review.item, saved)
            } catch (failure: Exception) {
                throw blocked(
                    "The video copy exists, but its review record could not be saved. Retry verifies and records that same copy. " +
                        failure.cleanerMessage
                )
            }
            review.savedID = saved
        }

    fun startCompressionBatch(ids: Set<String>) =
        run("Checking the selected photos…") {
            if (candidate.value != null || compressionBatch.value != null)
                throw blocked("Finish the current review first.")
            val persisted = repository.read()
            val order = media.value.filter { it.id in ids }.map { it.id }
            if (order.size != ids.size)
                throw blocked("A selected photo is no longer available. Read the library again.")
            val items =
                compressionReviewPlan(
                    order,
                    media.value,
                    persisted.copy(processedSources = persisted.processedSources + sessionCopyKeys),
                )
            error.value = null
            compressionBatch.value = CompressionBatch(items)
        }

    fun prepareBatchPhoto() =
        run("Preparing the next photo…") {
            if (candidate.value != null) throw blocked("Finish the current preview first.")
            val item = compressionBatch.value?.current ?: return@run
            error.value = null
            prepareCandidate(item)
        }

    private fun clearCandidate() {
        candidate.value?.output?.parentFile?.deleteRecursively()
        candidate.value = null
        candidateSource.value = null
        candidateItem.value = null
        createdCopyID.value = null
    }

    fun discardCandidate() {
        if (!busy.value) {
            clearCandidate()
            error.value = null
        }
    }

    fun skipBatchPhoto() {
        if (busy.value) return
        val queue = compressionBatch.value ?: return
        val item = queue.current ?: return
        val uncertain = createdCopyID.value != null
        clearCandidate()
        error.value = null
        compressionBatch.value =
            if (uncertain)
                queue.copy(index = queue.index + 1, unverified = queue.unverified + item.id)
            else queue.copy(index = queue.index + 1, skipped = queue.skipped + item.id)
    }

    fun stopCompressionBatch() {
        if (busy.value) return
        clearCandidate()
        compressionBatch.value = null
        error.value = null
    }

    /** Re-encode the retained original, never the previous lossy candidate. */
    fun updateCandidateQuality(
        quality: Double,
        size: ImageSizeProfile = candidate.value?.size ?: ImageSizeProfile.ORIGINAL,
    ) =
        run("Updating and checking the preview…") {
            if (createdCopyID.value != null)
                throw blocked(
                    "A copy already exists. Finish verifying it before changing this preview."
                )
            val previous = candidate.value ?: return@run
            error.value = null
            val next =
                ImageCompression.makeCandidate(
                    previous.source,
                    previous.output.parentFile!!,
                    quality,
                    size,
                )
            if (candidate.value === previous) {
                candidate.value = next
                previous.output.delete()
            } else next.output.delete()
        }

    fun saveCandidate() =
        run("Verifying and recording a copy; keeping the original…", cancellable = false) {
            val candidate = candidate.value ?: return@run
            val item =
                candidateItem.value
                    ?: throw blocked("The source snapshot is unavailable. Prepare a new preview.")
            error.value = null
            val existing = createdCopyID.value
            val saved =
                if (existing != null) {
                    photos.verifyCopy(candidate, existing)
                    existing
                } else
                    photos.saveCopy(candidate, item) { id ->
                        createdCopyID.value = id
                        sessionCopyKeys.add(compressionSourceKey(item))
                        sessionCopyKeys.add("output:$id")
                    }
            try {
                state.value = repository.recordCopy(item, saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw blocked(
                    "The copy was verified, but its review record could not be saved. Retry checks and records that same copy. The original remains. " +
                        failure.cleanerMessage
                )
            }
            val queue = compressionBatch.value
            clearCandidate()
            if (queue?.current?.id == item.id)
                compressionBatch.value =
                    queue.copy(index = queue.index + 1, saved = queue.saved + item.id)
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
            // The kept copy is written and read back first; the shareable file is a checked copy.
            contacts.export(snapshot, savedArchiveFile)
            exportFile.value = shareableArchive()
            savedContactArchive.value = readSavedArchive()
            state.value =
                repository.record(
                    "Exported ${snapshot.records.size} accessible contact records and kept the latest archive on this phone. Notes and account relationships are not included; originals retained."
                )
        }

    /** Prepares the kept archive for sharing; available after the trial and after a relaunch. */
    fun shareSavedContacts(onReady: (File) -> Unit) =
        run("Checking the saved contact archive…", paid = false, cancellable = false) {
            if (!savedArchiveFile.exists()) throw blocked("No contact archive is saved on this phone.")
            val file = shareableArchive()
            exportFile.value = file
            onReady(file)
        }

    fun deleteSavedContacts() =
        run("Deleting the saved contact archive…", paid = false, cancellable = false) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (savedArchiveFile.exists() && !savedArchiveFile.delete())
                    throw blocked("The saved contact archive could not be deleted.")
                File(cache, "exports").deleteRecursively()
            }
            exportFile.value = null
            savedContactArchive.value = null
            state.value =
                repository.record(
                    "Deleted the saved contact archive from this phone. Contacts are unchanged."
                )
        }

    /** A checked, decodable copy of the kept archive in the shared cache folder. */
    private suspend fun shareableArchive(): File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = savedArchiveFile.readBytes()
            contactPackageDecode(bytes.direct())
            val file = File(cache, "exports/Contacts-${UUID.randomUUID()}.cleanercontacts")
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            if (!file.readBytes().contentEquals(bytes)) {
                file.delete()
                throw blocked("The archive copy could not be checked. Nothing was shared.")
            }
            file
        }

    private suspend fun readSavedArchive(): SavedContactArchive? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!savedArchiveFile.exists()) return@withContext null
            val records =
                runCatching {
                        contactPackageDecode(savedArchiveFile.readBytes().direct()).records
                    }
                    .getOrNull()
            SavedContactArchive(records, Instant.ofEpochMilli(savedArchiveFile.lastModified()))
        }

    fun scanFolder(tree: Uri) =
        run("Reading the selected folder…") { folderScan.value = files.scan(tree, hash = true) }

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
