package gg.seven.cleaner

import androidx.core.util.AtomicFile
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.ReviewDecision
import gg.seven.cleaner.core.ReviewState
import gg.seven.cleaner.core.newHistoryEntry
import gg.seven.cleaner.core.newReviewState
import gg.seven.cleaner.core.reviewStateDecide
import gg.seven.cleaner.core.reviewStateDecode
import gg.seven.cleaner.core.reviewStateEncode
import gg.seven.cleaner.core.reviewStateRecord
import gg.seven.cleaner.core.reviewStateRecordCopy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Durable user decisions (iOS ReviewStore). Scans never write over these. A corrupt or newer file
 * fails closed instead of resetting. The state changes, the file format and its version gate are
 * the core's; the atomic file is this store's. Every change reads the file again first, so two
 * writers can't lose each other's decisions, and a failed write publishes nothing.
 */
class ReviewStore(file: File) {
    private val atomic = AtomicFile(file)
    private val lock = Mutex()

    suspend fun read(): ReviewState = lock.withLock { load() }

    suspend fun decide(
        decisions: Map<String, ReviewDecision>,
        revisions: Map<String, String>,
    ): ReviewState = change { reviewStateDecide(it, decisions, revisions) }

    suspend fun record(message: String, processedSource: String? = null): ReviewState = change {
        reviewStateRecord(it, newHistoryEntry(message), processedSource)
    }

    suspend fun recordCopy(source: CleanupItem, outputID: String): ReviewState = change {
        reviewStateRecordCopy(
            it,
            source,
            outputID,
            newHistoryEntry("Saved a compressed copy of ${source.name}. Original retained."),
        )
    }

    private suspend fun change(update: (ReviewState) -> ReviewState): ReviewState =
        lock.withLock {
            val state = update(load())
            write(state)
            state
        }

    private suspend fun load(): ReviewState =
        withContext(Dispatchers.IO) {
            if (!atomic.baseFile.exists()) newReviewState()
            else reviewStateDecode(atomic.readFully().direct())
        }

    private suspend fun write(state: ReviewState) =
        withContext(Dispatchers.IO) {
            atomic.baseFile.parentFile?.mkdirs()
            val bytes = reviewStateEncode(state)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
}
