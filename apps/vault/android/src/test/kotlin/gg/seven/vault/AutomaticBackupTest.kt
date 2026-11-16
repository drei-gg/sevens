package gg.seven.vault

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The delivery queue with a local folder writer: SevenBackup's behaviour, ported. */
class AutomaticBackupTest {
    private val base: File = Files.createTempDirectory("vault-queue").toFile()
    private val folder = File(base, "provider").apply { mkdirs() }

    @After fun clean() = base.deleteRecursively().let {}

    private class LocalWriter(var fail: Boolean = false) : BackupFolderWriter {
        override fun name(destination: String) = File(destination).name

        override fun write(source: File, destination: String, filename: String) {
            if (fail) throw BackupFailure.destination()
            source.copyTo(File(destination, filename))
        }

        override fun remove(destination: String, filename: String) {
            File(destination, filename).delete()
        }
    }

    private fun revision(n: Int) = n.toString().padStart(64, '0')

    private fun artifact(text: String) = File(base, "a-${UUID.randomUUID()}").apply { writeText(text) }

    @Test
    fun deliversKeepsThreeVersionsAndNeverTouchesOtherFiles() = runBlocking<Unit> {
        val writer = LocalWriter()
        val queue = AutomaticBackup(File(base, "queue"), writer)
        queue.prepare()
        val target = UUID.randomUUID()
        File(folder, "notes.txt").writeText("someone else's")
        queue.configure(target, folder.path)
        for (n in 1..5) {
            assertTrue(queue.needsPreparation(target, revision(n)))
            queue.enqueue(artifact("backup $n"), target, revision(n), "vault7")
            assertFalse(queue.needsPreparation(target, revision(n)))
            queue.deliverPending()
            val status = queue.status(target)!!
            assertEquals(revision(n), status.copiedRevision)
            assertNull(status.pendingRevision)
        }
        val names = folder.list()!!.sorted()
        assertEquals(4, names.size)
        assertTrue("notes.txt" in names)
        assertEquals((3..5).map { "${target.upper()}-${revision(it)}.vault7" }.sorted(), names.filter { it != "notes.txt" })
        assertFalse(queue.hasPending())
        queue.disable(target)
        assertEquals(4, folder.list()!!.size)
    }

    @Test
    fun failuresKeepThePendingCopyAndBackOffToAnHour() = runBlocking<Unit> {
        val writer = LocalWriter(fail = true)
        val queue = AutomaticBackup(File(base, "queue"), writer)
        val target = UUID.randomUUID()
        queue.configure(target, folder.path)
        queue.enqueue(artifact("pending"), target, revision(1), "vault7")
        var now = Instant.parse("2026-11-16T10:00:00Z")
        var last = 0L
        repeat(10) {
            queue.deliverPending(now, force = true)
            val status = queue.status(target)!!
            assertEquals(revision(1), status.pendingRevision)
            assertNotNull(status.failure)
            val wait = status.nextAttempt!!.epochSecond - now.epochSecond
            assertTrue(wait >= last && wait <= 3600)
            last = wait
            now = status.nextAttempt!!
        }
        assertEquals(3600, last)
        // Not due yet: skipped.
        writer.fail = false
        queue.deliverPending(now.minusSeconds(10))
        assertTrue(queue.hasPending())
        queue.deliverPending(now)
        assertFalse(queue.hasPending())
        assertEquals(1, folder.list()!!.size)
    }

    @Test
    fun prepareDiscardsUncommittedArtifactsOnly() = runBlocking<Unit> {
        val root = File(base, "queue")
        val queue = AutomaticBackup(root, LocalWriter(fail = true))
        val target = UUID.randomUUID()
        queue.configure(target, folder.path)
        queue.enqueue(artifact("keep"), target, revision(1), "vault7")
        File(root, UUID.randomUUID().upper() + ".vault7").writeText("orphan")
        File(queue.preparing(), "half.vault7").writeText("half")
        AutomaticBackup(root, LocalWriter()).prepare()
        assertEquals(2, root.list()!!.size) // destinations.json and the pending artifact
        assertTrue(queue.hasPending())
    }
}
