package gg.seven.cleaner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.MatchGroup
import gg.seven.cleaner.core.MediaKind
import gg.seven.cleaner.core.fileRevision
import gg.seven.cleaner.core.matchingExact
import gg.seven.cleaner.core.sortLargestFirst
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class FolderScan(
    val items: List<CleanupItem>,
    val groups: List<MatchGroup>,
    val skipped: Int,
    /** The provider isn't this phone's own storage, so files were listed without reading them. */
    val unhashed: Boolean = false,
)

/**
 * A folder the person picked with the system's folder picker (iOS FilesService over a
 * security-scoped URL): every file below it, sizes from the provider, and a SHA-256 of each one
 * so the core can group exact copies. Files from a cloud provider are listed but not read, so
 * nothing is downloaded for hashing; the same goes for virtual documents. Read-only.
 */
class FilesService(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun scan(tree: Uri, hash: Boolean): FolderScan =
        withContext(Dispatchers.IO) {
            val local = tree.authority in LOCAL_AUTHORITIES
            val root = DocumentsContract.getTreeDocumentId(tree)
            val items = mutableListOf<CleanupItem>()
            var skipped = 0
            val pending = ArrayDeque(listOf(root to ""))
            var readable = false
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val (parent, prefix) = pending.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                val cursor =
                    runCatching { resolver.query(children, COLUMNS, null, null, null) }.getOrNull()
                if (cursor == null) {
                    if (parent == root) throw blocked("This folder cannot be read.")
                    skipped += 1
                    continue
                }
                readable = true
                cursor.use { c ->
                    while (c.moveToNext()) {
                        coroutineContext.ensureActive()
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        val mime = c.getString(2)
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (mime == Document.MIME_TYPE_DIR) {
                            pending += id to path
                            continue
                        }
                        val size = if (c.isNull(3)) null else c.getLong(3)
                        val modified = if (c.isNull(4)) null else c.getLong(4)
                        val flags = if (c.isNull(5)) 0 else c.getInt(5)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        var item =
                            CleanupItem(
                                id = uri.toString(),
                                name = path,
                                kind = MediaKind.FILE,
                                revision = fileRevision(modified?.div(1000.0), size),
                                bytes = size,
                                date = modified?.let(Instant::ofEpochMilli),
                                width = 0,
                                height = 0,
                                duration = 0.0,
                                favorite = false,
                                tags = emptyList(),
                                fingerprint = null,
                            )
                        if (hash && local && flags and Document.FLAG_VIRTUAL_DOCUMENT == 0) {
                            try {
                                val stream = resolver.openInputStream(uri) ?: throw blocked("Unreadable.")
                                val digest = sha256(stream).first
                                val after = facts(uri)
                                // A file that changed while it was read is skipped, not trusted.
                                if (after == size to modified) item = item.copy(fingerprint = digest) else skipped += 1
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                skipped += 1
                            }
                        }
                        items += item
                    }
                }
            }
            if (!readable) throw blocked("This folder cannot be read.")
            val sorted = sortLargestFirst(items)
            FolderScan(sorted, matchingExact(sorted), skipped, unhashed = hash && !local)
        }

    private fun facts(uri: Uri): Pair<Long?, Long?>? =
        resolver.query(uri, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else (if (c.isNull(0)) null else c.getLong(0)) to (if (c.isNull(1)) null else c.getLong(1))
        }

    private companion object {
        val COLUMNS =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_FLAGS,
            )

        /** Providers whose files are on this phone; anything else may download to be read. */
        val LOCAL_AUTHORITIES = setOf("com.android.externalstorage.documents")
    }
}
