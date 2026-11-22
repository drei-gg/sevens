package gg.seven.scanner

import androidx.core.util.AtomicFile
import gg.seven.scanner.core.*
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * The library on disk (port of iOS ScannerCore/DocumentStore.swift and Backup.swift): one folder
 * per document (`document.json` plus one JPEG per page), Recently Deleted in `.trash`. The store
 * does the file I/O; the core decides every rule (what is valid, the manifest bytes, the order,
 * which folders count, which page images are left over, the backup format). Manifests commit
 * atomically after immutable page files. Every operation runs serialized on the IO dispatcher, as
 * the Swift actor does.
 */

/** The core's `&[u8]` parameters borrow a direct buffer from its start. */
internal fun ByteArray.direct(): ByteBuffer =
    ByteBuffer.allocateDirect(size).put(this).also { it.flip() }

class DocumentStore(val root: File) {
    private val lock = Mutex()
    private val limits = scanLimits()
    val trash: File
        get() = File(root, ".trash")

    /** Runs [block] alone, off the main thread. */
    suspend fun <T> io(block: DocumentStore.() -> T): T =
        lock.withLock { withContext(Dispatchers.IO) { block() } }

    fun list(deleted: Boolean = false): List<ScanDocument> {
        val listing = readLibrary(deleted)
        if (listing.unreadableCount != 0L) throw ScanException.InvalidDocument()
        return listing.documents
    }

    /** Healthy documents, newest first, and how many folders could not be read. */
    fun readLibrary(deleted: Boolean = false): LibraryListing {
        val base = if (deleted) trash else root
        base.mkdirs()
        val documents = mutableListOf<ScanDocument>()
        var failures = 0L
        for (folder in base.listFiles().orEmpty().filter { isDocumentFolder(it.name) }) {
            try {
                documents += readManifest(folder)
            } catch (_: Exception) {
                failures++
            }
        }
        return libraryListing(documents, failures)
    }

    /** A new document from prepared page files (copied in; the caller's files are left alone). */
    fun create(
        title: String,
        images: List<File>,
        text: List<String>,
        created: Double = nowReference(),
    ): ScanDocument {
        val doc = newDocumentFromScan(title, images.map { it.length().toULong() }, text, created)
        root.mkdirs()
        val staging = File(root, ".pending-" + documentFolderName(doc.id))
        if (!staging.mkdir()) throw ScanException.InvalidDocument()
        try {
            doc.pages.forEachIndexed { index, page ->
                writeAtomically(File(staging, pageFileName(page.id))) {
                    images[index].inputStream().use { input -> input.copyTo(it) }
                }
            }
            writeAtomically(File(staging, MANIFEST)) { it.write(manifestEncode(doc)) }
            if (!staging.renameTo(folder(doc.id))) throw ScanException.InvalidDocument()
            return doc
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun save(document: ScanDocument) {
        documentValidate(document)
        for (page in document.pages) if (!imageFile(document.id, page.id).isFile)
            throw ScanException.MissingPage()
        writeAtomically(File(folder(document.id), MANIFEST)) { it.write(manifestEncode(document)) }
        removeUnlistedPages(folder(document.id), document)
    }

    /** A removed page's image goes when the manifest that dropped it is committed, never before. */
    private fun removeUnlistedPages(folder: File, document: ScanDocument) {
        val names = folder.list()?.toList() ?: return
        for (name in unlistedPageFiles(document, names)) File(folder, name).delete()
    }

    /**
     * Launch housekeeping: staging folders left by an interrupted create or restore, and page
     * images an earlier build left behind after removing a page. Unreadable documents are never
     * touched.
     */
    fun cleanUp(): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        for (item in root.listFiles().orEmpty().filter { isStagingFolder(it.name) }) {
            item.deleteRecursively()
            removed++
        }
        for (base in listOf(root, trash).filter { it.isDirectory }) {
            for (folder in base.listFiles().orEmpty().filter { isDocumentFolder(it.name) }) {
                val doc =
                    runCatching {
                            manifestRead(File(folder, MANIFEST).readBytes().direct(), folder.name)
                        }
                        .getOrNull() ?: continue
                val before = folder.list()?.size ?: 0
                removeUnlistedPages(folder, doc)
                removed += before - (folder.list()?.size ?: before)
            }
        }
        return removed
    }

    /** Page image files in document order. [deleted] reads a document in Recently Deleted. */
    fun pageFiles(document: ScanDocument, deleted: Boolean = false): List<File> {
        documentValidate(document)
        val base = File(if (deleted) trash else root, documentFolderName(document.id))
        return document.pages.map { page ->
            File(base, pageFileName(page.id)).also {
                if (!it.isFile) throw ScanException.MissingPage()
                if (it.length() > limits.maxPageBytes) throw ScanException.TooLarge()
            }
        }
    }

    /** New settings for one page; its recognized text is cleared, since it was read before. */
    fun updatePage(documentId: UUID, pageId: UUID, settings: PageSettings): ScanDocument {
        pageSettingsValidate(settings)
        val current = documentWithPageSettings(load(documentId), pageId, settings)
        save(current)
        return current
    }

    fun updateAnnotations(
        documentId: UUID,
        expected: ScanPage,
        annotations: List<PageAnnotation>,
        ink: List<PageInk> = expected.ink,
    ): ScanDocument {
        val current = documentWithPageMarks(load(documentId), expected, annotations, ink)
        save(current)
        return current
    }

    fun updatePageText(documentId: UUID, expected: ScanPage, text: String): ScanDocument {
        val current = documentWithPageText(load(documentId), expected, text)
        save(current)
        return current
    }

    fun append(documentId: UUID, images: List<File>): ScanDocument {
        val current = load(documentId)
        val existing = pageFiles(current).sumOf { it.length() }
        checkAppendImages(
            current.pages.size.toULong(),
            images.map { it.length().toULong() },
            existing.toULong(),
        )
        val pages = images.map { newScanPage("") }
        try {
            pages.zip(images).forEach { (page, file) ->
                writeAtomically(imageFile(current.id, page.id)) { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            }
            val next = current.copy(pages = current.pages + pages)
            save(next)
            return next
        } catch (error: Exception) {
            pages.forEach { imageFile(current.id, it.id).delete() }
            throw error
        }
    }

    /**
     * The listed pages in that order; unlisted pages are removed. [expected] refuses a stale copy.
     */
    fun setPageOrder(
        documentId: UUID,
        pageIds: List<UUID>,
        expected: List<UUID>? = null,
    ): ScanDocument {
        val current = documentWithPageOrder(load(documentId), pageIds, expected)
        save(current)
        return current
    }

    fun updateMetadata(
        documentId: UUID,
        title: String,
        note: String,
        favorite: Boolean,
    ): ScanDocument {
        val current = documentWithMetadata(load(documentId), title, note, favorite)
        save(current)
        return current
    }

    /** Moving the whole folder keeps every page and the manifest together; never overwrites. */
    fun delete(document: ScanDocument) {
        trash.mkdirs()
        move(folder(document.id), trashFolder(document.id))
    }

    fun restore(document: ScanDocument) = move(trashFolder(document.id), folder(document.id))

    fun permanentlyDelete(document: ScanDocument) {
        val folder = trashFolder(document.id)
        if (!folder.isDirectory || !folder.deleteRecursively())
            throw ScanException.InvalidDocument()
    }

    /** OCR completion never overwrites a rename, reorder or edit made while recognition ran. */
    fun fillRecognizedText(
        documentId: UUID,
        byPage: Map<UUID, String>,
        expected: Map<UUID, PageSettings>? = null,
        overwrite: Boolean = false,
        expectedPages: List<ScanPage>? = null,
    ): ScanDocument {
        val current = documentDecode(File(folder(documentId), MANIFEST).readBytes().direct())
        val filled =
            expectedPages?.let { documentApplyRecognition(current, it, byPage, overwrite) }
                ?: documentFillRecognizedText(current, byPage, expected, overwrite)
        save(filled)
        return filled
    }

    // MARK: backup

    /**
     * The portable `.scannerbackup`, streamed: Recently Deleted included, source files unchanged.
     * An unreadable document blocks backup rather than silently producing an incomplete copy.
     */
    fun exportBackup(output: OutputStream) {
        val entries = mutableListOf<BackupEntry>()
        val files = mutableListOf<File>()
        for (deleted in listOf(false, true)) {
            for (document in list(deleted)) {
                val pages = pageFiles(document, deleted)
                entries += BackupEntry(document, deleted, pages.map { it.length() })
                files += pages
            }
        }
        output.write(backupHeader(entries))
        var index = 0
        for (entry in entries) {
            for (size in entry.sizes) {
                val checksum = files[index].inputStream().use { stream(it, output, size) }
                output.write(backupChecksumBytes(checksum))
                index++
            }
        }
        output.flush()
    }

    /**
     * Checks every record and checksum in staging, then adds copies with new document IDs. Existing
     * documents are never replaced; deleted entries restore into Recently Deleted. An ordinary
     * failure while committing removes the copies already added.
     */
    fun restoreBackup(input: InputStream, length: Long): Int {
        val preamble = exact(input, backupMagic().size + 4)
        val indexLength = backupIndexLength(length.toULong(), preamble.direct())
        val index =
            readBackupIndex(length.toULong(), exact(input, indexLength.toInt() + 4).direct())
        root.mkdirs()
        val staging = File(root, ".restore-" + UUID.randomUUID().toString().uppercase())
        if (!staging.mkdir()) throw ScanException.InvalidDocument()
        try {
            val additions = mutableListOf<Pair<File, File>>()
            for (entry in index.entries) {
                val document = restoredCopy(entry.document)
                val name = documentFolderName(document.id)
                val folder = File(staging, name).apply { mkdir() }
                document.pages.zip(entry.sizes).forEach { (page, size) ->
                    val checksum =
                        File(folder, pageFileName(page.id)).outputStream().use {
                            stream(input, it, size)
                        }
                    if (!exact(input, 4).contentEquals(backupChecksumBytes(checksum)))
                        throw ScanException.InvalidDocument()
                }
                writeAtomically(File(folder, MANIFEST)) { it.write(manifestEncode(document)) }
                val base = if (entry.deleted) trash else root
                base.mkdirs()
                additions += folder to File(base, name)
            }
            val committed = mutableListOf<File>()
            try {
                for ((from, to) in additions) {
                    move(from, to)
                    committed += to
                }
            } catch (error: Exception) {
                committed.forEach { it.deleteRecursively() }
                throw error
            }
            return additions.size
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Exactly [count] bytes; a file that ends early is not a backup. */
    private fun exact(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(bytes, read, count - read)
            if (n < 0) throw ScanException.InvalidDocument()
            read += n
        }
        return bytes
    }

    /** Copies [count] bytes and returns the core's CRC-32 of them. */
    private fun stream(input: InputStream, output: OutputStream, count: Long): UInt {
        val chunk = ByteArray(CHUNK)
        val buffer = ByteBuffer.allocateDirect(CHUNK)
        var checksum = 0u
        var remaining = count
        while (remaining > 0) {
            val n = input.read(chunk, 0, minOf(CHUNK.toLong(), remaining).toInt())
            if (n < 0) throw ScanException.InvalidDocument()
            buffer.clear()
            buffer.put(chunk, 0, n).flip()
            checksum = crc32Continue(checksum, buffer)
            output.write(chunk, 0, n)
            remaining -= n
        }
        return checksum
    }

    // MARK: files

    internal fun load(id: UUID): ScanDocument =
        manifestRead(File(folder(id), MANIFEST).readBytes().direct(), documentFolderName(id))

    /** A folder's manifest, refused unread when it is larger than any manifest can be. */
    private fun readManifest(folder: File): ScanDocument {
        val file = File(folder, MANIFEST)
        if (file.length() > limits.maxManifestBytes) throw ScanException.TooLarge()
        return manifestRead(file.readBytes().direct(), folder.name)
    }

    fun folder(id: UUID) = File(root, documentFolderName(id))

    /** Where one page's image lives, for thumbnails (no checks; [pageFiles] checks). */
    fun pageFile(document: UUID, page: UUID, deleted: Boolean = false) =
        File(File(if (deleted) trash else root, documentFolderName(document)), pageFileName(page))

    private fun trashFolder(id: UUID) = File(trash, documentFolderName(id))

    private fun imageFile(document: UUID, page: UUID) = File(folder(document), pageFileName(page))

    private fun move(from: File, to: File) {
        if (to.exists() || !from.renameTo(to)) throw ScanException.InvalidDocument()
    }

    companion object {
        const val MANIFEST = "document.json"
        private const val CHUNK = 1_048_576

        /** Seconds since 2001-01-01 UTC, the core's `created` (Foundation's reference date). */
        fun nowReference(): Double = System.currentTimeMillis() / 1000.0 - REFERENCE_EPOCH

        const val REFERENCE_EPOCH = 978_307_200.0

        /** Replaces [file] atomically; a failed write leaves the old file in place. */
        fun writeAtomically(file: File, body: (OutputStream) -> Unit) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                body(out)
                atomic.finishWrite(out)
            } catch (error: Throwable) {
                atomic.failWrite(out)
                throw if (error is EOFException) ScanException.InvalidDocument() else error
            }
        }
    }
}

/** `created` as epoch milliseconds. */
val ScanDocument.createdMillis: Long
    get() = ((created + DocumentStore.REFERENCE_EPOCH) * 1000).toLong()

val ScanPage.settings: PageSettings
    get() = scanPageSettings(this)
