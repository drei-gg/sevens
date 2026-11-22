package gg.seven.vault

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Port of iOS SevenBackup's AutomaticBackup: durable delivery of already prepared, encrypted
 * artifacts to a folder the person chose (here a Storage Access Framework tree with a persisted
 * permission). This layer never handles keys. A provider accepting a file is not confirmation that
 * a cloud upload finished.
 */

data class BackupStatus(
    val target: UUID,
    val destination: String,
    val copiedAt: Instant? = null,
    val copiedRevision: String? = null,
    val pendingRevision: String? = null,
    val failure: BackupFailure.Reason? = null,
    val nextAttempt: Instant? = null,
)

/** Why a delivery failed. The status keeps the reason, not words, so it shows in today's language. */
class BackupFailure(val reason: Reason) : Exception(reason.code) {
    enum class Reason(val code: String) {
        /** The folder is gone or no longer writable. */
        DESTINATION("destination"),
        /** The prepared artifact is missing or malformed. */
        INVALID_ARTIFACT("invalid-artifact"),
        /** The copy in the folder doesn't match the artifact. */
        CHANGED_COPY("changed-copy"),
        /** Anything else the provider threw while copying. */
        COPY("copy");

        companion object {
            /** A stored reason; an unknown one (or an older build's sentence) reads as a failed copy. */
            fun of(code: String) = entries.firstOrNull { it.code == code } ?: COPY
        }
    }

    companion object {
        fun destination() = BackupFailure(Reason.DESTINATION)

        fun invalidArtifact() = BackupFailure(Reason.INVALID_ARTIFACT)

        fun changedCopy() = BackupFailure(Reason.CHANGED_COPY)
    }
}

/** Writes into, and removes from, the chosen folder. `destination` is the folder's URI string. */
interface BackupFolderWriter {
    fun name(destination: String): String

    fun write(source: File, destination: String, filename: String)

    fun remove(destination: String, filename: String)
}

private data class BackupRecord(
    var status: BackupStatus,
    val destination: String,
    var pendingFile: String? = null,
    var failures: Int = 0,
    var delivered: List<String> = emptyList(),
)

class AutomaticBackup(val root: File, private val writer: BackupFolderWriter) {
    private val mutex = Mutex()
    private val stateFile get() = File(root, "destinations.json")

    private suspend fun <T> serial(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun records(): MutableList<BackupRecord> {
        if (!stateFile.exists()) return mutableListOf()
        val array = JSONArray(stateFile.readText())
        return MutableList(array.length()) { decode(array.getJSONObject(it)) }
    }

    private fun save(records: List<BackupRecord>) {
        VaultRepository.protectedDirectory(root)
        VaultRepository.atomicWrite(stateFile, JSONArray(records.map(::encode)).toString().toByteArray())
    }

    /** Call once at launch before producers start. Interrupted, uncommitted artifacts are disposable. */
    suspend fun prepare() = serial {
        val all = records()
        VaultRepository.protectedDirectory(root)
        val retained = all.mapNotNull { it.pendingFile }.toSet()
        root.listFiles()?.forEach {
            if (it.name == "preparing" || (isUuidPrefixed(it.name) && it.name !in retained)) it.deleteRecursively()
        }
    }

    suspend fun configure(target: UUID, destination: String) = serial {
        val name = writer.name(destination)
        val all = records()
        val old = all.firstOrNull { it.status.target == target }
        all.removeAll { it.status.target == target }
        all += BackupRecord(BackupStatus(target, name), destination)
        save(all)
        old?.pendingFile?.let { artifact(it).delete() }
    }

    /** Turning automatic backup off leaves existing destination copies intact. */
    suspend fun disable(target: UUID) = serial {
        val all = records()
        val old = all.firstOrNull { it.status.target == target }
        all.removeAll { it.status.target == target }
        save(all)
        old?.pendingFile?.let { artifact(it).delete() }
    }

    suspend fun status(target: UUID): BackupStatus? = serial { records().firstOrNull { it.status.target == target }?.status }

    suspend fun hasPending(): Boolean = serial { records().any { it.pendingFile != null } }

    /** When the earliest pending delivery may next be tried, or null when nothing is pending. */
    suspend fun nextAttempt(): Instant? = serial {
        records().filter { it.pendingFile != null }.minOfOrNull { it.status.nextAttempt ?: Instant.EPOCH }
    }

    suspend fun needsPreparation(target: UUID, revision: String): Boolean = serial {
        val record = records().firstOrNull { it.status.target == target } ?: return@serial false
        record.status.pendingRevision != revision && record.status.copiedRevision != revision
    }

    fun preparing(): File = File(root, "preparing").also { VaultRepository.protectedDirectory(it) }

    suspend fun enqueue(source: File, target: UUID, revision: String, extension: String) = serial {
        if (revision.length != 64 || !revision.all { it in "0123456789abcdef" } || extension.isEmpty() || !extension.all { it in 'a'..'z' || it in '0'..'9' })
            throw BackupFailure.invalidArtifact()
        val all = records()
        val index = all.indexOfFirst { it.status.target == target }
        if (index < 0) return@serial
        VaultRepository.protectedDirectory(root)
        val name = UUID.randomUUID().upper() + "." + extension
        val output = File(root, name)
        source.copyTo(output)
        val old = all[index].pendingFile
        all[index].pendingFile = name
        all[index].status = all[index].status.copy(pendingRevision = revision, failure = null, nextAttempt = null)
        all[index].failures = 0
        try {
            save(all)
        } catch (e: Exception) {
            output.delete()
            throw e
        }
        old?.let { artifact(it).delete() }
    }

    suspend fun deliverPending(now: Instant = Instant.now(), force: Boolean = false) = serial {
        var all = records()
        for (index in all.indices) {
            val record = all[index]
            val file = record.pendingFile ?: continue
            val revision = record.status.pendingRevision ?: continue
            if (!force && (record.status.nextAttempt ?: Instant.EPOCH) > now) continue
            kotlin.coroutines.coroutineContext.ensureActive()
            try {
                val filename = record.status.target.upper() + "-" + revision + "." + file.substringAfterLast('.')
                writer.write(artifact(file), record.destination, filename)
                // Retain three successfully copied versions from this queue. Never delete unrelated files.
                val previous = record.delivered.filter { it != filename }
                record.delivered = (listOf(filename) + previous).take(3)
                record.status = record.status.copy(copiedRevision = revision, copiedAt = now, pendingRevision = null, failure = null, nextAttempt = null)
                record.pendingFile = null
                record.failures = 0
                save(all)
                artifact(file).delete()
                for (old in previous.drop(2)) runCatching { writer.remove(record.destination, old) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the artifact and previous receipt on every failed delivery. Reload in case
                // persisting the success receipt itself failed.
                all = records()
                val failed = all[index]
                failed.failures += 1
                val delay = min(3600.0, 30 * 2.0.pow(min(failed.failures, 7)))
                failed.status = failed.status.copy(failure = (e as? BackupFailure)?.reason ?: BackupFailure.Reason.COPY, nextAttempt = now.plusSeconds(delay.toLong()))
                save(all)
            }
        }
    }

    private fun artifact(name: String): File {
        if (name.contains('/') || !isUuidPrefixed(name)) throw BackupFailure.invalidArtifact()
        return File(root, name)
    }

    private fun isUuidPrefixed(name: String) = name.length >= 36 && runCatching { UUID.fromString(name.take(36)) }.isSuccess

    private fun encode(record: BackupRecord) =
        JSONObject().apply {
            val s = record.status
            put("target", s.target.upper())
            put("destinationName", s.destination)
            s.copiedAt?.let { put("copiedAt", it.toEpochMilli()) }
            s.copiedRevision?.let { put("copiedRevision", it) }
            s.pendingRevision?.let { put("pendingRevision", it) }
            s.failure?.let { put("failure", it.code) }
            s.nextAttempt?.let { put("nextAttempt", it.toEpochMilli()) }
            put("destination", record.destination)
            record.pendingFile?.let { put("pendingFile", it) }
            put("failures", record.failures)
            put("delivered", JSONArray(record.delivered))
        }

    private fun decode(o: JSONObject): BackupRecord {
        fun string(key: String) = if (o.has(key)) o.getString(key) else null
        fun instant(key: String) = if (o.has(key)) Instant.ofEpochMilli(o.getLong(key)) else null
        val delivered = o.optJSONArray("delivered")
        return BackupRecord(
            BackupStatus(
                UUID.fromString(o.getString("target")),
                o.getString("destinationName"),
                instant("copiedAt"),
                string("copiedRevision"),
                string("pendingRevision"),
                string("failure")?.let(BackupFailure.Reason::of),
                instant("nextAttempt"),
            ),
            o.getString("destination"),
            string("pendingFile"),
            o.optInt("failures"),
            List(delivered?.length() ?: 0) { delivered!!.getString(it) },
        )
    }
}

fun sha256(input: InputStream): ByteArray {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    input.use {
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    return hash.digest()
}

/** The Storage Access Framework writer: a persisted tree permission, partial then verified files. */
class DocumentTreeWriter(private val context: Context) : BackupFolderWriter {
    private fun tree(destination: String): DocumentFile {
        val folder = DocumentFile.fromTreeUri(context, destination.toUri())
        if (folder == null || !folder.isDirectory || !folder.canWrite()) throw BackupFailure.destination()
        return folder
    }

    override fun name(destination: String) = tree(destination).name ?: context.getString(R.string.automatic_chosen_folder)

    override fun write(source: File, destination: String, filename: String) {
        val folder = tree(destination)
        val resolver = context.contentResolver
        val expected = source.inputStream().let(::sha256)
        folder.findFile(filename)?.let { existing ->
            val actual = resolver.openInputStream(existing.uri)?.let(::sha256) ?: throw BackupFailure.destination()
            if (!actual.contentEquals(expected)) throw BackupFailure.changedCopy()
            return
        }
        fun copy(name: String): DocumentFile {
            val created = folder.createFile("application/octet-stream", name) ?: throw BackupFailure.destination()
            try {
                (resolver.openOutputStream(created.uri, "w") ?: throw BackupFailure.destination()).use { out -> source.inputStream().use { it.copyTo(out, 1 shl 20) } }
                val copied = resolver.openInputStream(created.uri)?.let(::sha256)
                if (copied == null || !copied.contentEquals(expected)) throw BackupFailure.changedCopy()
            } catch (e: Exception) {
                created.delete()
                throw e
            }
            return created
        }
        val partial = copy(".backup-" + UUID.randomUUID().upper())
        // Publish under the final name; a provider that cannot rename gets the file written again
        // under it, verified the same way.
        if (!(runCatching { partial.renameTo(filename) }.getOrDefault(false) && partial.name == filename)) {
            partial.delete()
            copy(filename).takeIf { it.name == filename } ?: throw BackupFailure.destination()
        }
    }

    override fun remove(destination: String, filename: String) {
        tree(destination).findFile(filename)?.delete()
    }
}

/** WorkManager's chance to deliver already prepared backups, also while the vault is locked. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as VaultApplication
        return try {
            app.automatic.deliverPending()
            schedule(applicationContext, app.automatic.nextAttempt(), fromWorker = true)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "vault-backup-delivery"

        /**
         * One delivery opportunity at `at` (at least a minute away, as on iOS), or none when
         * nothing is pending. Timing is the system's: no exact interval is promised.
         */
        fun schedule(context: Context, at: Instant?, fromWorker: Boolean = false) {
            val work = WorkManager.getInstance(context)
            if (at == null) return
            val delay = maxOf(60_000L, at.toEpochMilli() - System.currentTimeMillis())
            val request =
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                    .build()
            // The worker queues its successor after itself; the app never cancels one in flight.
            work.enqueueUniqueWork(NAME, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
