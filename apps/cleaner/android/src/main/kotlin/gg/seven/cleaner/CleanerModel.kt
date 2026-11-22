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

    private val res
        get() = getApplication<Application>().resources

    private fun str(id: Int, vararg args: Any): String = res.getString(id, *args)

    private fun plural(id: Int, count: Int, vararg args: Any): String =
        res.getQuantityString(id, count, *args)

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
                showError(failure.cleanerMessage(res))
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
                str(R.string.model_paid_feature)
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
                    progress.value = str(R.string.model_cancelled)
                } catch (failure: Exception) {
                    showError(failure.cleanerMessage(res))
                } finally {
                    withContext(NonCancellable) {
                        runCatching { savedMeasurements.value = measurements.count() }
                            .onFailure { showError(it.cleanerMessage(res)) }
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
        progress.value = str(R.string.model_stopping)
    }

    fun scanPhotos() =
        run(str(R.string.model_reading_media)) {
            val inventory = photos.inventory()
            media.value = measurements.restore(inventory.items)
            savedMeasurements.value = media.value.count { scanHasMeasurement(it) }
            limitedPhotos.value = inventory.limited
            groups.value = emptyList()
            selected.value = emptySet()
        }

    fun analyze() =
        run(str(R.string.model_reading_originals)) {
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
                    progress.value = str(R.string.model_progress_checked, done, total, resumed)
                    comparisonFraction.value = if (total == 0) 1f else done.toFloat() / total
                }
            media.value = result.items
            groups.value = result.groups
            state.value =
                repository.record(
                    plural(
                        R.plurals.model_compared,
                        result.items.size,
                        result.items.size,
                        result.unavailable,
                    )
                )
        }

    fun clearScanProgress() =
        run(str(R.string.model_clearing_measurements), paid = false, cancellable = false) {
            measurements.clear()
            savedMeasurements.value = 0
            media.update { items -> items.map { it.copy(fingerprint = null, bytes = null) } }
            groups.value = emptyList()
            selected.value = emptySet()
        }

    fun findSimilar() =
        run(str(R.string.model_comparing_similar)) {
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
                showError(failure.cleanerMessage(res))
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
        run(str(R.string.model_applying_deletion), cancellable = false) {
            // The core names each item that changed, became a favorite or is gone; only the
            // ready ones are offered to the system, and the rest are marked for review again.
            val check = photos.removalCheck(ids, state.value)
            replaceItems(ids, check.current)
            val ready = removalReadyIds(check.outcomes).toSet()
            val held = removalHeldMessage(check.outcomes)
            if (ready.isEmpty()) throw blocked(held ?: str(R.string.model_nothing_selected))
            val intent = photos.trashRequest(ready, state.value)
            systemRequest.value =
                SystemRequest(intent) { confirmed ->
                    if (confirmed)
                        run(str(R.string.model_applying_deletion), cancellable = false) {
                            val gone = ready - photos.remaining(ready)
                            if (gone.isNotEmpty()) {
                                state.value =
                                    repository.record(
                                        plural(R.plurals.model_deleted, gone.size, gone.size)
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
     * confirmation. Their bytes stay the same; only the folder changes.
     */
    fun addToAlbum(ids: Set<String>, title: String) =
        run(str(R.string.model_checking_selection), cancellable = false) {
            val items = media.value.filter { it.id in ids }
            if (items.size != ids.size || items.isEmpty())
                throw blocked(str(R.string.model_item_unavailable))
            val intent = photos.moveRequest(items, title)
            systemRequest.value =
                SystemRequest(intent) { confirmed ->
                    if (confirmed)
                        run(str(R.string.model_moving_album), cancellable = false) {
                            finishAlbum(photos.moveToAlbum(items, title), ids, title)
                        }
                }
        }

    private suspend fun finishAlbum(
        result: AlbumResult,
        ids: Set<String>,
        title: String,
    ) {
        // A moved file has a new revision; decisions made before the move need review again.
        refreshItems(ids)
        val name = title.trim()
        state.value =
            repository.record(
                plural(R.plurals.model_album_moved, ids.size, result.done.size, ids.size, name)
            )
        if (result.failed.isNotEmpty())
            showError(plural(R.plurals.model_album_failed, ids.size, result.failed.size, ids.size))
    }

    fun setFavorite(item: CleanupItem) =
        run(str(R.string.model_updating_favorite), cancellable = false) {
            val intent = photos.favoriteRequest(item.id, !item.favorite)
            systemRequest.value =
                SystemRequest(intent) { confirmed ->
                    if (confirmed)
                        viewModelScope.launch {
                            // A favorite change gives the item a new provider revision; take it,
                            // so later copies and decisions bind to what the library now has.
                            runCatching { refreshItems(setOf(item.id)) }
                                .onFailure { showError(it.cleanerMessage(res)) }
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
            candidate.value = ImageCompression.makeCandidate(getApplication(), original, directory)
            candidateSource.value = item.id
            candidateItem.value = item
            createdCopyID.value = null
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    fun compress(item: CleanupItem) =
        run(str(R.string.model_preparing_candidate)) {
            if (compressionBatch.value != null || candidate.value != null)
                throw blocked(str(R.string.model_finish_review))
            prepareCandidate(item)
        }

    fun openVideo(item: CleanupItem) =
        run(str(R.string.model_reading_video)) {
            if (
                videoReview.value != null ||
                    candidate.value != null ||
                    compressionBatch.value != null
            )
                throw blocked(str(R.string.model_finish_review))
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
                        VideoCompression.inspect(res, source)
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
        run(str(R.string.model_preparing_range)) {
            val review = videoReview.value ?: return@run
            if (review.createdID != null) throw blocked(str(R.string.model_finish_existing_copy))
            val plan =
                review.plan() ?: throw blocked(str(R.string.model_invalid_range))
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
        run(str(R.string.model_saving_video), cancellable = false) {
            val review = videoReview.value ?: return@run
            val preview = review.candidate ?: return@run
            if (review.plan() != preview.plan || preview.issues.isNotEmpty())
                throw blocked(str(R.string.model_prepare_range_first))
            error.value = null
            val saved =
                review.createdID?.also { photos.verifySavedFile(preview.output, it) }
                    ?: photos.saveVideoCopy(preview, review.item) { id ->
                        review.createdID = id
                        sessionCopyKeys.add(compressionSourceKey(review.item))
                        sessionCopyKeys.add("output:$id")
                    }
            try {
                state.value =
                    repository.recordCopy(
                        review.item,
                        saved,
                        str(R.string.model_saved_copy, review.item.name),
                    )
            } catch (failure: Exception) {
                throw blocked(
                    str(R.string.model_video_record_failed, failure.cleanerMessage(res))
                )
            }
            review.savedID = saved
        }

    fun startCompressionBatch(ids: Set<String>) =
        run(str(R.string.model_checking_photos)) {
            if (candidate.value != null || compressionBatch.value != null)
                throw blocked(str(R.string.model_finish_review))
            val persisted = repository.read()
            val order = media.value.filter { it.id in ids }.map { it.id }
            if (order.size != ids.size)
                throw blocked(str(R.string.model_photo_unavailable))
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
        run(str(R.string.model_preparing_next)) {
            if (candidate.value != null) throw blocked(str(R.string.model_finish_preview))
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
        run(str(R.string.model_updating_preview)) {
            if (createdCopyID.value != null)
                throw blocked(
                    str(R.string.model_copy_exists)
                )
            val previous = candidate.value ?: return@run
            error.value = null
            val next =
                ImageCompression.makeCandidate(
                    getApplication(),
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
        run(str(R.string.model_saving_copy), cancellable = false) {
            val candidate = candidate.value ?: return@run
            val item =
                candidateItem.value
                    ?: throw blocked(str(R.string.model_snapshot_unavailable))
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
                state.value =
                    repository.recordCopy(item, saved, str(R.string.model_saved_copy, item.name))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw blocked(
                    str(R.string.model_copy_record_failed, failure.cleanerMessage(res))
                )
            }
            val queue = compressionBatch.value
            clearCandidate()
            if (queue?.current?.id == item.id)
                compressionBatch.value =
                    queue.copy(index = queue.index + 1, saved = queue.saved + item.id)
        }

    fun scanContacts(region: String?) =
        run(str(R.string.model_reading_contacts)) {
            val snapshot = contacts.scan(region)
            contactSnapshot.value = snapshot
            contactMatches.value = contactCandidates(snapshot.records)
        }

    fun exportContacts() =
        run(str(R.string.model_writing_archive), paid = false) {
            val snapshot = contactSnapshot.value ?: return@run
            // The kept copy is written and read back first; the shareable file is a checked copy.
            contacts.export(snapshot, savedArchiveFile)
            exportFile.value = shareableArchive()
            savedContactArchive.value = readSavedArchive()
            state.value =
                repository.record(
                    plural(R.plurals.model_exported, snapshot.records.size, snapshot.records.size)
                )
        }

    /** Prepares the kept archive for sharing; available after the trial and after a relaunch. */
    fun shareSavedContacts(onReady: (File) -> Unit) =
        run(str(R.string.model_checking_archive), paid = false, cancellable = false) {
            if (!savedArchiveFile.exists()) throw blocked(str(R.string.model_no_archive))
            val file = shareableArchive()
            exportFile.value = file
            onReady(file)
        }

    fun deleteSavedContacts() =
        run(str(R.string.model_deleting_archive), paid = false, cancellable = false) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (savedArchiveFile.exists() && !savedArchiveFile.delete())
                    throw blocked(str(R.string.model_archive_not_deleted))
                File(cache, "exports").deleteRecursively()
            }
            exportFile.value = null
            savedContactArchive.value = null
            state.value =
                repository.record(
                    str(R.string.model_archive_deleted)
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
                throw blocked(str(R.string.model_archive_unchecked))
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
        run(str(R.string.model_reading_folder)) { folderScan.value = files.scan(tree, hash = true) }

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
