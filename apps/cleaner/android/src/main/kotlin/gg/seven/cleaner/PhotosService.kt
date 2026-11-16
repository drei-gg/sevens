package gg.seven.cleaner

import androidx.core.graphics.scale
import androidx.core.net.toUri
import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.MatchGroup
import gg.seven.cleaner.core.MediaKind
import gg.seven.cleaner.core.ReviewState
import gg.seven.cleaner.core.SimilarityWindow
import gg.seven.cleaner.core.checkCopyOffer
import gg.seven.cleaner.core.matchingExact
import gg.seven.cleaner.core.perceptualDistance
import gg.seven.cleaner.core.perceptualHash
import gg.seven.cleaner.core.photoFingerprint
import gg.seven.cleaner.core.photoResourcePart
import gg.seven.cleaner.core.photoRevision
import gg.seven.cleaner.core.photoTags
import gg.seven.cleaner.core.similarHashGroup
import gg.seven.cleaner.core.similarityOrder
import gg.seven.cleaner.core.validateRemoval
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PhotoInventory(val items: List<CleanupItem>, val limited: Boolean)

data class PhotoAnalysis(val items: List<CleanupItem>, val groups: List<MatchGroup>, val unavailable: Int)

/**
 * The media library through MediaStore (iOS PhotosService over PhotoKit). Items are identified by
 * their content URI. Reads work on every supported Android; the changes Android only lets an app
 * make through a system confirmation (moving to the trash, favorites) need Android 11, and
 * saving a copy needs Android 10.
 */
class PhotosService(private val context: Context) {
    private val resolver = context.contentResolver

    /** What the person allowed: everything, only chosen items (Android 14+), or nothing. */
    fun access(): Access {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 ->
                when {
                    granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) -> Access.Full
                    Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Limited
                    else -> Access.None
                }
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.Full
            else -> Access.None
        }
    }

    enum class Access { Full, Limited, None }

    suspend fun inventory(): PhotoInventory =
        withContext(Dispatchers.IO) {
            val access = access()
            if (access == Access.None) throw blocked("Photos access was not granted. You can change it in Settings.")
            val items = (query(images, null, null) + query(videos, null, null)).sortedByDescending { it.date ?: Instant.MIN }
            PhotoInventory(items, access == Access.Limited)
        }

    /** The item as the library has it now, or null when it's gone or no longer accessible. */
    private fun current(id: String): CleanupItem? {
        val uri = id.toUri()
        val collection = if (isVideo(uri)) videos else images
        return query(collection, "${MediaStore.MediaColumns._ID} = ?", arrayOf(ContentUris.parseId(uri).toString())).firstOrNull()
    }

    private fun isVideo(uri: Uri) = uri.pathSegments.contains("video")

    private fun query(collection: Uri, selection: String?, args: Array<String>?): List<CleanupItem> {
        val video = collection == videos
        val columns = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(DATE_TAKEN)
            if (video) add(MediaStore.Video.VideoColumns.DURATION)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_FAVORITE)
            if (video && Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.CAPTURE_FRAMERATE)
        }
        val items = mutableListOf<CleanupItem>()
        resolver.query(collection, columns.toTypedArray(), selection, args, null)?.use { c ->
            while (c.moveToNext()) items += item(c, collection, video)
        }
        return items
    }

    private fun item(c: Cursor, collection: Uri, video: Boolean): CleanupItem {
        fun long(column: String) = c.getColumnIndex(column).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
        fun text(column: String) = c.getColumnIndex(column).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
        val id = ContentUris.withAppendedId(collection, long(MediaStore.MediaColumns._ID) ?: 0)
        val name = text(MediaStore.MediaColumns.DISPLAY_NAME) ?: if (video) "Video" else "Photo"
        val width = (long(MediaStore.MediaColumns.WIDTH) ?: 0).toInt()
        val height = (long(MediaStore.MediaColumns.HEIGHT) ?: 0).toInt()
        val modified = long(MediaStore.MediaColumns.DATE_MODIFIED)
        val taken = long(DATE_TAKEN)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val added = long(MediaStore.MediaColumns.DATE_ADDED)?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
        val frameRate = c.getColumnIndex("capture_framerate").takeIf { it >= 0 && !c.isNull(it) }?.let(c::getFloat)
        val traits = mediaTraits(video, text("relative_path"), name, frameRate)
        return CleanupItem(
            id = id.toString(),
            name = name,
            kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO,
            // One resource per item on Android; the modification date is in seconds, as stored.
            revision = photoRevision(modified?.toDouble(), width, height, 1),
            // MediaStore records each file's size; "Find duplicates" measures it again by reading.
            bytes = long(MediaStore.MediaColumns.SIZE)?.takeIf { it > 0 },
            date = taken ?: added,
            width = width,
            height = height,
            duration = (long(MediaStore.Video.VideoColumns.DURATION) ?: 0) / 1000.0,
            favorite = (long("is_favorite") ?: 0) == 1L,
            tags = photoTags(traits),
            fingerprint = null,
        )
    }

    private fun mime(id: String): String? = resolver.getType(id.toUri())

    /** Every readable byte of each item, hashed; the core groups byte-equal items. */
    suspend fun analyze(input: List<CleanupItem>, progress: suspend (Int, Int) -> Unit): PhotoAnalysis =
        withContext(Dispatchers.IO) {
            val items = input.toMutableList()
            var unavailable = 0
            for (i in items.indices) {
                coroutineContext.ensureActive()
                val item = items[i]
                try {
                    if (current(item.id)?.revision != item.revision) throw blocked("Asset changed.")
                    val stream = resolver.openInputStream(item.id.toUri()) ?: throw blocked("No resources.")
                    val (hash, bytes) = sha256(stream)
                    val part = photoResourcePart(if (item.kind == MediaKind.VIDEO) 2 else 1, mime(item.id).orEmpty(), hash)
                    // Re-check against edits made while reading.
                    if (current(item.id)?.revision != item.revision) throw blocked("Asset changed.")
                    items[i] = item.copy(fingerprint = photoFingerprint(listOf(part)), bytes = bytes)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unavailable += 1
                    items[i] = item.copy(fingerprint = null, bytes = null)
                }
                progress(i + 1, items.size)
            }
            PhotoAnalysis(items, matchingExact(items), unavailable)
        }

    /**
     * Nearby stills compared by a perceptual hash (the core's), through the core's capture-time
     * window. Suggestions for review only; the threshold is provisional, as Vision's is on iOS.
     */
    suspend fun similar(input: List<CleanupItem>): List<MatchGroup> =
        withContext(Dispatchers.IO) {
            val window = SimilarityWindow()
            val prints = mutableMapOf<String, ULong>()
            val groups = mutableListOf<MatchGroup>()
            for (item in similarityOrder(input)) {
                coroutineContext.ensureActive()
                val date = item.date ?: continue
                val recent = window.prune(date)
                prints.keys.retainAll(recent.toSet())
                val print = thumbnailBitmap(item.id, 64)?.let(::hashOf) ?: continue
                for (id in recent) {
                    val earlier = prints[id] ?: continue
                    similarHashGroup(id, item.id, perceptualDistance(earlier, print))?.let(groups::add)
                }
                window.admit(item.id, date)
                prints[item.id] = print
            }
            window.close()
            groups
        }

    private fun hashOf(bitmap: Bitmap): ULong? {
        val small = bitmap.softwareCopy().scale(32, 32)
        val buffer = ByteBuffer.allocateDirect(32 * 32 * 4)
        small.copyPixelsToBuffer(buffer)
        buffer.flip()
        return runCatching { perceptualHash(buffer) }.getOrNull()
    }

    private val thumbnails =
        object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

    /** A thumbnail at about [pixels] on its longer side, or null when it can't be read. */
    fun thumbnailBitmap(id: String, pixels: Int): Bitmap? {
        val key = "$id@$pixels"
        thumbnails.get(key)?.let { return it }
        val uri = id.toUri()
        val bitmap =
            runCatching {
                    if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(uri, Size(pixels, pixels), null)
                    else if (isVideo(uri))
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(resolver, ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
                    else decodeSampled(uri, pixels)
                }
                .getOrNull() ?: return null
        thumbnails.put(key, bitmap)
        return bitmap
    }

    /** The still decoded for inspection, at most [maxSide] pixels on its longer side. */
    fun previewBitmap(uri: Uri, maxSide: Int = 3072): Bitmap? = runCatching { decodeSampled(uri, maxSide) }.getOrNull()

    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    /**
     * Copies a still's original bytes into [directory] for compression. Motion photos carry a
     * video and need a separate preservation profile, as Live Photos do on iOS.
     */
    suspend fun originalStill(item: CleanupItem, directory: File): File =
        withContext(Dispatchers.IO) {
            val now = current(item.id)
            if (now == null || now.kind != MediaKind.PHOTO || "Live Photos" in now.tags)
                throw blocked("Choose a still photo. Motion photo conversion needs a separate preservation profile.")
            directory.mkdirs()
            val extension = when (mime(item.id)) { "image/png" -> "png"; "image/jpeg" -> "jpg"; else -> "img" }
            val file = File(directory, "${UUID.randomUUID()}.$extension")
            try {
                resolver.openInputStream(item.id.toUri())?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: throw blocked("Photo is no longer accessible.")
                file
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }

    /**
     * The system's confirmation to move reviewed items to the trash, after the core's removal
     * guard has checked them against the library as it is now: every item still there, marked for
     * removal at its current revision, and not a favorite.
     */
    suspend fun trashRequest(ids: Set<String>, state: ReviewState): PendingIntent =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 30) throw blocked("Moving media to the trash needs Android 11 or later. Nothing was changed.")
            val current = ids.mapNotNull(::current)
            validateRemoval(ids.sorted(), current, state)
            MediaStore.createTrashRequest(resolver, ids.map { it.toUri() }, true)
        }

    /** The ids of [ids] still in the library (trashed items are not). */
    suspend fun remaining(ids: Set<String>): Set<String> = withContext(Dispatchers.IO) { ids.filter { current(it) != null }.toSet() }

    suspend fun favoriteRequest(id: String, value: Boolean): PendingIntent =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 30) throw blocked("Favorites need Android 11 or later.")
            current(id) ?: throw blocked("Photo is no longer accessible.")
            MediaStore.createFavoriteRequest(resolver, listOf(id.toUri()), value)
        }

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) { current(id)?.favorite == true }

    /**
     * Saves a compressed candidate as a new item next to its source (same folder, which is what
     * Android's gallery apps call an album, same capture date, favorite if the source is one),
     * then reads it back and compares the saved bytes with the candidate. Returns the new id.
     */
    suspend fun saveCopy(candidate: CompressionCandidate, source: CleanupItem): String =
        withContext(Dispatchers.IO) {
            checkCopyOffer(candidate.assessment)
            if (Build.VERSION.SDK_INT < 29) throw blocked("Saving copies needs Android 10 or later. The original is unchanged.")
            val now = current(source.id)
            if (now == null || now.revision != source.revision) throw blocked("The source changed or is no longer accessible. Prepare a new candidate.")
            val folder = relativePath(source.id) ?: "Pictures/"
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, source.name.substringBeforeLast('.') + " compressed.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    source.date?.let { put(DATE_TAKEN, it.toEpochMilli()) }
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri = resolver.insert(images, values) ?: throw blocked("Could not create the copy. The original is unchanged.")
            try {
                resolver.openOutputStream(uri)?.use { out -> candidate.output.inputStream().use { it.copyTo(out) } }
                    ?: throw blocked("Could not write the copy.")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (Build.VERSION.SDK_INT >= 30 && now.favorite) put(MediaStore.MediaColumns.IS_FAVORITE, 1)
                }, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw if (error is gg.seven.cleaner.core.CleanerException) error else blocked("The copy could not be saved. The original is unchanged.")
            }
            try {
                val saved = resolver.openInputStream(uri)?.let { sha256(it).first }
                val expected = sha256(candidate.output.inputStream()).first
                if (saved != expected) throw blocked("Saved bytes differ.")
            } catch (_: Exception) {
                throw blocked("The copy was created, but saved-byte verification could not complete. The original remains. Check your gallery before retrying.")
            }
            uri.toString()
        }

    private fun relativePath(id: String): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return resolver.query(id.toUri(), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    private companion object {
        val images: Uri =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videos: Uri =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        /** MediaColumns.DATE_TAKEN on 29+, the same column on the image and video tables before. */
        const val DATE_TAKEN = "datetaken"
    }
}

internal fun Bitmap.softwareCopy(): Bitmap =
    if (config != Bitmap.Config.ARGB_8888) copy(Bitmap.Config.ARGB_8888, false) else this
