package gg.seven.alarm

import android.util.AtomicFile
import gg.seven.alarm.core.LoopArchive
import gg.seven.alarm.core.LoopException
import gg.seven.alarm.core.backupDecode
import gg.seven.alarm.core.backupMaxBytes
import gg.seven.alarm.core.newLoopArchive
import gg.seven.alarm.core.storeEncode
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the app shows when something is refused: one sentence for the person (iOS LoopFailure). */
class LoopFailure(message: String) : Exception(message)

/** The core's error as the sentence iOS shows for it. */
val Throwable.sentence: String
    get() =
        when (this) {
            is LoopException.Invalid -> reason
            is LoopException.Unreadable ->
                "The data couldn’t be read because it isn’t in the correct format."
            else -> message ?: "Something went wrong."
        }

/** Runs a core call, turning its `LoopException` into a [LoopFailure] sentence. */
inline fun <T> core(call: () -> T): T =
    try {
        call()
    } catch (e: LoopException) {
        throw LoopFailure(e.sentence)
    }

val maxBackupBytes: Long = backupMaxBytes().toLong()

/** The core's decode (validation included); it reads the bytes in place, so they go in a direct buffer. */
fun decodeArchive(bytes: ByteArray): LoopArchive {
    val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
    buffer.flip()
    return core { backupDecode(buffer) }
}

/**
 * The archive's file (iOS AlarmStore `ArchiveFile`): read once, then each change is validated and
 * encoded by the core (`storeEncode`, which also refuses an oversized file), written atomically,
 * and published only after the write succeeded. A file that can't be read is never overwritten:
 * every change reads first, so a failed read stops it. `file = null` keeps the archive in memory
 * (tests).
 */
class ArchiveRepository(private val file: File?, seed: LoopArchive = newLoopArchive()) {
    private val lock = Mutex()
    private val atomic = file?.let(::AtomicFile)
    private var cache = seed
    private var loaded = file == null

    private fun readLocked(): LoopArchive {
        if (!loaded) {
            val atomic = atomic!!
            if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
                val bytes = atomic.openRead().use { it.readBytes() }
                if (bytes.size > maxBackupBytes) throw LoopFailure("Archive exceeds 32 MB.")
                cache = decodeArchive(bytes)
            }
            loaded = true
        }
        return cache
    }

    suspend fun read(): LoopArchive = withContext(Dispatchers.IO) { lock.withLock { readLocked() } }

    suspend fun change(action: (LoopArchive) -> LoopArchive): LoopArchive =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val next = action(readLocked())
                val bytes = core { storeEncode(next) }
                atomic?.let { file ->
                    file.baseFile.parentFile?.mkdirs()
                    val stream = file.startWrite()
                    try {
                        stream.write(bytes)
                        file.finishWrite(stream)
                    } catch (e: Exception) {
                        file.failWrite(stream)
                        throw e
                    }
                }
                cache = next
                next
            }
        }
}
