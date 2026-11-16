package gg.seven.vault

import gg.seven.vault.core.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The repository on the real core (JNA, the crate built for this machine): the storage around
 * format version 2 that Kotlin owns. The cryptographic gates themselves are the core's tests.
 */
class VaultRepositoryTest {
    private val base: File = Files.createTempDirectory("vault-repo").toFile()
    private fun repository(name: String = "vault") = VaultRepository(File(base, name), File(base, "$name-tmp"))
    private val secret = "several private testing words"

    @After fun clean() = base.deleteRecursively().let {}

    private fun source(name: String, bytes: ByteArray) = File(base, name).apply { writeBytes(bytes) }

    @Test
    fun createImportLockUnlockAndExportRoundTrips() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        val created = repo.create("Travel", secret, VaultCredentialKind.PASSPHRASE)
        assertEquals(44, created.recoveryKey.length)
        val data = ByteArray(2_500_000) { (it * 7).toByte() }
        val item = repo.importFile(source("photo.jpg", data).path, "photo.jpg", "public.jpeg")
        repo.addNote("Checklist", "Passport\nCharger", null)
        repo.lock()
        assertThrows(VaultException.Locked::class.java) { runBlocking { repo.snapshot() } }
        assertTrue("temporary plaintext is cleared on lock", repo.temporary.listFiles().isNullOrEmpty())
        val library = repo.unlock(secret, false, VaultCredentialKind.PASSPHRASE)
        assertEquals(listOf("photo.jpg", "Checklist.txt"), library.items.map { it.name })
        assertArrayEquals(data, repo.export(item.id).readBytes())
        assertEquals("Passport\nCharger", repo.readNote(library.items[1].id))
        // Nothing in the vault directory is plaintext.
        val folder = File(repo.root, library.id.upper())
        assertEquals(setOf("header.json", "index.age", blobNameV2(item.id), blobNameV2(library.items[1].id)), folder.list()!!.toSet())
        assertFalse(String(File(folder, "index.age").readBytes(), Charsets.ISO_8859_1).contains("photo.jpg"))
        // The recovery key opens it too.
        repo.lock()
        assertEquals(library.id, repo.unlock(created.recoveryKey, true, VaultCredentialKind.PASSPHRASE).id)
    }

    @Test
    fun wrongCredentialsAndIndependentVaults() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        val a = repo.create("A", secret, VaultCredentialKind.PASSPHRASE).library
        val b = repo.create("B", "00123456", VaultCredentialKind.PIN).library
        repo.lock()
        assertThrows(VaultException.InvalidCredential::class.java) { runBlocking { repo.unlock("wrong secret words", false, VaultCredentialKind.PASSPHRASE) } }
        assertThrows(VaultException.InvalidCredential::class.java) { runBlocking { repo.unlock("00123456", false, VaultCredentialKind.PASSPHRASE) } }
        assertEquals(b.id, repo.unlock("00123456", false, VaultCredentialKind.PIN).id)
        assertEquals(a.id, repo.unlock(secret, false, VaultCredentialKind.PASSPHRASE).id)
        // A secret that already opens another vault can't be reused.
        assertThrows(VaultException.DuplicateVault::class.java) { runBlocking { repo.create("C", secret, VaultCredentialKind.PASSPHRASE) } }
        assertThrows(VaultException.DuplicateVault::class.java) { runBlocking { repo.changeCredential("00123456", VaultCredentialKind.PIN) } }
    }

    @Test
    fun credentialRotationKeepsContentBytes() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        val library = repo.create("Rotate", secret, VaultCredentialKind.PASSPHRASE).library
        val item = repo.importFile(source("a.bin", ByteArray(70_000) { 3 }).path, "a.bin", "public.data")
        val folder = File(repo.root, library.id.upper())
        val before = File(folder, blobNameV2(item.id)).readBytes() to File(folder, "index.age").readBytes()
        repo.changeCredential("0,5,10,15,20,21,22,23,24", VaultCredentialKind.PATTERN)
        assertArrayEquals(before.first, File(folder, blobNameV2(item.id)).readBytes())
        assertArrayEquals(before.second, File(folder, "index.age").readBytes())
        repo.lock()
        assertThrows(VaultException.InvalidCredential::class.java) { runBlocking { repo.unlock(secret, false, VaultCredentialKind.PASSPHRASE) } }
        assertEquals(1, repo.unlock("0,5,10,15,20,21,22,23,24", false, VaultCredentialKind.PATTERN).items.size)
    }

    @Test
    fun trashRestoreAndPermanentDelete() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        val library = repo.create("Trash", secret, VaultCredentialKind.PASSPHRASE).library
        val item = repo.importFile(source("t.txt", "x".toByteArray()).path, "t.txt", "public.plain-text")
        repo.updateItem(item.id, trashed = true)
        assertNotNull(repo.snapshot().items.single().deleted)
        repo.updateItem(item.id, trashed = false)
        assertNull(repo.snapshot().items.single().deleted)
        assertThrows(VaultException.Damaged::class.java) { runBlocking { repo.permanentlyDelete(setOf(item.id)) } }
        repo.updateItem(item.id, trashed = true)
        repo.permanentlyDelete(setOf(item.id))
        assertTrue(repo.snapshot().items.isEmpty())
        assertFalse(File(File(repo.root, library.id.upper()), blobNameV2(item.id)).exists())
    }

    @Test
    fun backupRestoresIntoCleanStorageAndNeverOverwrites() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        val created = repo.create("Backup", secret, VaultCredentialKind.PASSPHRASE)
        repo.importFile(source("p.png", ByteArray(300_000) { 9 }).path, "p.png", "public.png")
        val backup = repo.backup().copyTo(File(base, "copy.vault7"))
        assertNotNull(repo.snapshot().backupPrepared)
        assertEquals("7DVAULT2", String(backup.readBytes().copyOf(8)))
        // Same storage: the vault exists, so restore is refused and nothing changes.
        assertThrows(VaultException.DuplicateVault::class.java) { runBlocking { repo.restore(backup.path, secret, false, VaultCredentialKind.PASSPHRASE) } }
        // Clean storage (another device).
        val other = repository("other").apply { prepare() }
        assertThrows(VaultException.InvalidCredential::class.java) { runBlocking { other.restore(backup.path, "wrong secret words", false, VaultCredentialKind.PASSPHRASE) } }
        val restored = other.restore(backup.path, created.recoveryKey, true, VaultCredentialKind.PASSPHRASE)
        assertEquals(created.library.id, restored.id)
        assertEquals(1, restored.items.size)
        assertArrayEquals(ByteArray(300_000) { 9 }, other.export(restored.items[0].id).readBytes())
        assertTrue("no staging left", other.root.list()!!.none { it.startsWith(".stage-") })
    }

    @Test
    fun damagedOrOldBackupsAreRefusedAndLeaveNothing() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        repo.create("Tamper", secret, VaultCredentialKind.PASSPHRASE)
        repo.importFile(source("p.bin", ByteArray(100_000) { 1 }).path, "p.bin", "public.data")
        val good = repo.backup().readBytes()
        val other = repository("other").apply { prepare() }
        fun attempt(bytes: ByteArray): VaultException {
            val file = File(base, "attempt-${UUID.randomUUID()}").apply { writeBytes(bytes) }
            return assertThrows(VaultException::class.java) { runBlocking { other.restore(file.path, secret, false, VaultCredentialKind.PASSPHRASE) } }
        }
        assertTrue(attempt(good.copyOf(good.size - 10)) is VaultException.Damaged)
        assertTrue(attempt(good.copyOf().also { it[it.size - 500] = (it[it.size - 500] + 1).toByte() }) is VaultException.Damaged)
        assertTrue(attempt(good + byteArrayOf(0)) is VaultException.Damaged)
        assertTrue(attempt("7DVAULT1".toByteArray() + good.copyOfRange(8, good.size)) is VaultException.OlderBackup)
        assertTrue(attempt("PK\u0003\u0004 not a backup".toByteArray()) is VaultException.Unsupported)
        assertTrue(other.root.list()!!.isEmpty())
    }

    @Test
    fun interruptedImportLeavesNothingAndPrepareClearsStaging() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        val library = repo.create("Interrupt", secret, VaultCredentialKind.PASSPHRASE).library
        assertThrows(VaultException.MissingFile::class.java) { runBlocking { repo.importFile(File(base, "missing").path, "m.bin", "public.data") } }
        val folder = File(repo.root, library.id.upper())
        assertEquals(setOf("header.json", "index.age"), folder.list()!!.toSet())
        assertTrue(repo.snapshot().items.isEmpty())
        File(folder, ".import-leftover").writeText("x")
        File(repo.root, ".stage-leftover").mkdirs()
        repository().prepare()
        assertEquals(setOf("header.json", "index.age"), folder.list()!!.toSet())
        assertFalse(File(repo.root, ".stage-leftover").exists())
    }

    @Test
    fun lockWaitsForAnInFlightImportWhichStillCommits() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        repo.create("Async", secret, VaultCredentialKind.PASSPHRASE)
        val big = source("big.bin", ByteArray(20_000_000) { 5 })
        val import = async { repo.importFile(big.path, "big.bin", "public.data") }
        val lock = async { repo.lock() }
        import.await()
        lock.await()
        assertThrows(VaultException.Locked::class.java) { runBlocking { repo.snapshot() } }
        assertEquals(1, repo.unlock(secret, false, VaultCredentialKind.PASSPHRASE).items.size)
    }

    @Test
    fun custodyUnlocksOnlyItsVault() = runBlocking<Unit> {
        val repo = repository().apply { prepare() }
        val library = repo.create("Bio", secret, VaultCredentialKind.PASSPHRASE).library
        val (id, material) = repo.custodyMaterial()
        assertEquals(library.id, id)
        repo.lock()
        assertEquals(library.id, repo.unlock(id, material).id)
        val other = repo.create("Other", "different testing words", VaultCredentialKind.PASSPHRASE).library
        repo.lock()
        assertThrows(VaultException.InvalidCredential::class.java) { runBlocking { repo.unlock(other.id, material) } }
    }
}
