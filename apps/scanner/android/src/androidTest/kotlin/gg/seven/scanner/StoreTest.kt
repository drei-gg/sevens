package gg.seven.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.scanner.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Android store's file I/O around the core's rules (the platform half of iOS ScannerCoreTests). */
@RunWith(AndroidJUnit4::class)
class StoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: DocumentStore
    private lateinit var scratch: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "store-test-${UUID.randomUUID()}")
        scratch = File(context.cacheDir, "scratch-${UUID.randomUUID()}").apply { mkdirs() }
        store = DocumentStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        scratch.deleteRecursively()
    }

    private fun image(text: String): File = File(scratch, "${UUID.randomUUID()}.jpg").also { Processing.normalize(Demo.page(text), it) }

    @Test
    fun createPersistsManifestAndPagesInCoreBytes() {
        val doc = store.create("Receipt", listOf(image("A"), image("B")), listOf("a", "b"))
        val folder = store.folder(doc.id)
        assertArrayEquals(manifestEncode(doc), File(folder, DocumentStore.MANIFEST).readBytes())
        assertEquals(3, folder.list()!!.size)
        assertEquals(listOf(doc), DocumentStore(root).list())
        assertFalse(root.list()!!.any { it.startsWith(".pending-") })
    }

    @Test
    fun removingAPageDeletesItsImageAndStaleOrderIsRefused() {
        val doc = store.create("Two", listOf(image("A"), image("B")), listOf("", ""))
        val kept = store.setPageOrder(doc.id, listOf(doc.pages[1].id), doc.pages.map { it.id })
        assertEquals(1, kept.pages.size)
        assertFalse(File(store.folder(doc.id), pageFileName(doc.pages[0].id)).exists())
        val appended = store.append(doc.id, listOf(image("C")))
        assertEquals(2, appended.pages.size)
        // An order made before the append must not silently drop the new page.
        assertThrows(ScanException.InvalidDocument::class.java) { store.setPageOrder(doc.id, listOf(doc.pages[1].id), kept.pages.map { it.id }) }
    }

    @Test
    fun lateRecognitionNeverOverwritesAnEditedPage() {
        val doc = store.create("OCR", listOf(image("A")), listOf(""))
        val page = doc.pages[0]
        store.updatePage(doc.id, page.id, page.settings.copy(quarterTurns = 1))
        val filled = store.fillRecognizedText(doc.id, mapOf(page.id to "stale"), mapOf(page.id to page.settings))
        assertEquals("", filled.pages[0].text)
        assertEquals(1L, filled.pages[0].settings.quarterTurns)
    }

    @Test
    fun trashRestoreAndPermanentDelete() {
        val doc = store.create("Trash me", listOf(image("A")), listOf(""))
        store.delete(doc)
        assertTrue(store.list().isEmpty())
        assertEquals(listOf(doc.id), store.list(deleted = true).map { it.id })
        store.restore(doc)
        assertEquals(listOf(doc.id), store.list().map { it.id })
        store.delete(doc)
        store.permanentlyDelete(doc)
        assertTrue(store.list(deleted = true).isEmpty())
        assertFalse(File(store.trash, documentFolderName(doc.id)).exists())
    }

    @Test
    fun unreadableFolderIsIsolatedAndBlocksBackup() {
        store.create("Healthy", listOf(image("A")), listOf(""))
        File(root, UUID.randomUUID().toString().uppercase()).apply { mkdirs() }.resolve(DocumentStore.MANIFEST).writeText("{not json")
        val listing = store.readLibrary()
        assertEquals(1, listing.documents.size)
        assertEquals(1L, listing.unreadableCount)
        assertThrows(ScanException.InvalidDocument::class.java) { store.exportBackup(ByteArrayOutputStream()) }
    }

    @Test
    fun cleanUpRemovesStagingAndLeftoverImages() {
        val doc = store.create("Keep", listOf(image("A")), listOf(""))
        File(root, ".pending-X").mkdirs()
        File(root, ".restore-Y").mkdirs()
        File(store.folder(doc.id), pageFileName(UUID.randomUUID())).writeBytes(byteArrayOf(1))
        assertEquals(3, store.cleanUp())
        assertEquals(2, store.folder(doc.id).list()!!.size)
    }

    @Test
    fun backupRoundTripsAndCorruptionLeavesLibraryUnchanged() {
        val a = store.create("Active", listOf(image("A"), image("B")), listOf("one", "two"))
        val b = store.create("Gone", listOf(image("C")), listOf("three"))
        store.updateMetadata(a.id, "Active", "Note", true)
        store.updatePage(a.id, a.pages.first().id, pageSettingsDefault().copy(crop = PageCrop(0.1, 0.2, 0.8, 0.9)))
        store.delete(b)
        val bytes = ByteArrayOutputStream().also { store.exportBackup(it) }.toByteArray()
        assertArrayEquals(backupMagic(), bytes.copyOfRange(0, 9))

        val other = DocumentStore(File(root.path + "-restored"))
        try {
            assertEquals(2, other.restoreBackup(ByteArrayInputStream(bytes), bytes.size.toLong()))
            val restored = other.list().single()
            assertNotEquals(a.id, restored.id)
            assertEquals(2L, restored.version)
            assertEquals(PageCrop(0.1, 0.2, 0.8, 0.9), restored.pages.first().settings.crop)
            assertEquals("Note", restored.note)
            assertEquals(true, restored.favorite)
            assertEquals("Gone", other.list(deleted = true).single().title)
            val original = store.pageFiles(store.list().single()).map { it.readBytes() }
            val copied = other.pageFiles(restored).map { it.readBytes() }
            original.zip(copied).forEach { (x, y) -> assertArrayEquals(x, y) }

            // One flipped byte in the last page: refused, nothing added.
            val corrupt = bytes.copyOf().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
            assertThrows(ScanException.InvalidDocument::class.java) { other.restoreBackup(ByteArrayInputStream(corrupt), corrupt.size.toLong()) }
            assertEquals(1, other.list().size)
            // A truncated file is refused by length before anything is staged.
            assertThrows(ScanException::class.java) { other.restoreBackup(ByteArrayInputStream(bytes), bytes.size.toLong() - 1) }
            assertFalse(other.root.list()!!.any { isStagingFolder(it) })
        } finally {
            other.root.deleteRecursively()
        }
    }

    /** A backup whose index Swift's JSONEncoder wrote (the core's golden fixture) restores on Android. */
    @Test
    fun restoresAnIndexWrittenBySwift() {
        val index = InstrumentationRegistry.getInstrumentation().context.assets.open("swift-encoder-backup-index.json").use { it.readBytes() }
        val entries = org.json.JSONObject(String(index)).getJSONArray("entries")
        val sizes = (0 until entries.length()).flatMap { e -> entries.getJSONObject(e).getJSONArray("sizes").let { a -> (0 until a.length()).map { a.getLong(it) } } }
        val deleted = (0 until entries.length()).count { entries.getJSONObject(it).getBoolean("deleted") }
        val file = ByteArrayOutputStream()
        file.write(backupMagic())
        file.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(index.size).array())
        file.write(withChecksum(index))
        val pages = sizes.map { size -> ByteArray(size.toInt()) { (it % 251).toByte() } }
        pages.forEach { file.write(it); file.write(backupChecksumBytes(crc32(it.direct()))) }
        val bytes = file.toByteArray()
        assertEquals(entries.length(), store.restoreBackup(ByteArrayInputStream(bytes), bytes.size.toLong()))
        assertEquals(entries.length() - deleted, store.list().size)
        assertEquals(deleted, store.list(deleted = true).size)
        val receipt = store.list().first { it.title == "Receipt" }
        assertEquals(true, receipt.pages[0].settings.grayscale)
        assertEquals(pages[0].size.toLong(), store.pageFiles(receipt)[0].length())
    }

    private fun withChecksum(index: ByteArray) = index + backupChecksumBytes(crc32(index.direct()))
}
