package gg.seven.cleaner

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.cleaner.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real media library on the device: items this test adds to MediaStore (its own, so it can
 * remove them again), read back through PhotosService and judged by the core.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photos = PhotosService(context)
    private val added = mutableListOf<Uri>()
    private val folder = "Pictures/CleanerTest-${UUID.randomUUID().toString().take(8)}/"

    @Before
    fun grant() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val permissions =
            if (Build.VERSION.SDK_INT >= 33)
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissions.forEach { automation.grantRuntimePermission(context.packageName, it) }
    }

    @After
    fun remove() {
        added.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    private fun scene(shift: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (y in 0 until 900) canvas.drawRect(
            0f,
            y.toFloat(),
            1200f,
            y + 1f,
            Paint().apply { color = Color.rgb(40 + y / 8, 100 + y / 10, 160) },
        )
        canvas.drawCircle(
            800f + shift,
            220f,
            90f,
            Paint().apply { color = Color.rgb(250, 222, 158) },
        )
        canvas.drawRect(0f, 600f, 1200f, 900f, Paint().apply { color = Color.rgb(30, 70, 74) })
        return ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            .toByteArray()
    }

    private fun insert(
        name: String,
        bytes: ByteArray,
        takenMillis: Long,
        captureMetadata: Boolean = false,
    ): String {
        // MediaStore derives DATE_TAKEN from the file when publishing. A database-only value on
        // an EXIF-free fixture falls back to DATE_ADDED, making preservation pass only within
        // the same second. Give the compression source a real capture date to preserve.
        val publishedBytes =
            if (captureMetadata) {
                val fixture = File.createTempFile("dated-fixture-", ".jpg", context.cacheDir)
                try {
                    fixture.writeBytes(bytes)
                    androidx.exifinterface.media.ExifInterface(fixture).apply {
                        setAttribute(
                            androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL,
                            java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
                                .withZone(java.time.ZoneOffset.UTC)
                                .format(java.time.Instant.ofEpochMilli(takenMillis)),
                        )
                        setAttribute(
                            androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                            "+00:00",
                        )
                        saveAttributes()
                    }
                    fixture.readBytes()
                } finally {
                    fixture.delete()
                }
            } else bytes
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.DATE_TAKEN, takenMillis)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                values,
            )!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(publishedBytes) }
        // Publishing it makes MediaStore scan the file (dimensions, size) before it's listed.
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        added += uri
        return uri.toString()
    }

    @Test
    fun checkpointWriteFailureLeavesReviewRecordsUntouched() = runBlocking {
        val folder =
            File(context.cacheDir, "checkpoint-failure-${UUID.randomUUID()}").apply { mkdirs() }
        val review = ReviewStore(File(folder, "review.json"))
        review.decide(mapOf("a" to ReviewDecision.KEEP), mapOf("a" to "1"))
        val before = File(folder, "review.json").readBytes()
        val obstruction = File(folder, "not-a-directory").apply { writeText("retain") }
        val cache = MeasurementCache(context, File(obstruction, "measurements.sqlite"))
        try {
            try {
                cache.checkpoint(
                    newCleanupItem("a", "A", MediaKind.PHOTO)
                        .copy(revision = "1", fingerprint = "hash", bytes = 42)
                )
                fail("Expected durable storage failure")
            } catch (_: android.database.sqlite.SQLiteException) {}
            assertArrayEquals(before, File(folder, "review.json").readBytes())
            assertEquals("retain", obstruction.readText())
        } finally {
            cache.close()
            folder.deleteRecursively()
        }
    }

    @Test
    fun interruptedComparisonResumesAfterReopenAndInvalidatesChangedAssets() = runBlocking {
        val now = System.currentTimeMillis()
        val a = insert("resume-a.jpg", scene(0), now)
        val b = insert("resume-b.jpg", scene(0), now)
        val ids = setOf(a, b)
        val file = File(context.noBackupFilesDir, "resume-test-${UUID.randomUUID()}.sqlite")
        var cache = MeasurementCache(context, file)
        try {
            val current = photos.inventory().items.filter { it.id in ids }
            try {
                photos.analyze(current, checkpoint = cache::checkpoint) { done, _ ->
                    if (done == 1)
                        throw kotlinx.coroutines.CancellationException(
                            "Test interruption after durable checkpoint"
                        )
                }
                fail("Expected interruption")
            } catch (_: kotlinx.coroutines.CancellationException) {}
            assertEquals(1, cache.count())
            cache.close()
            cache = MeasurementCache(context, file)
            val resumed = cache.restore(photos.inventory().items.filter { it.id in ids })
            assertEquals(1, resumed.count { scanHasMeasurement(it) })
            val result = photos.analyze(resumed, checkpoint = cache::checkpoint) { _, _ -> }
            assertEquals(0, result.unavailable)
            assertEquals(ids, result.groups.single().members.toSet())
            assertEquals(2, cache.count())
            // A native provider change must alter the generation even within the same second.
            context.contentResolver.update(
                Uri.parse(a),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "resume-renamed.jpg")
                },
                null,
                null,
            )
            val fresh = photos.inventory().items.filter { it.id in ids }
            assertNotEquals(
                current.single { it.id == a }.revision,
                fresh.single { it.id == a }.revision,
            )
            val changed = cache.restore(fresh)
            assertNull(changed.single { it.id == a }.fingerprint)
            assertNotNull(changed.single { it.id == b }.fingerprint)
            assertEquals(1, cache.count())
            // A fresh restricted inventory must also remove checkpoints outside the grant.
            cache.restore(fresh.filter { it.id == a })
            assertEquals(0, cache.count())
            cache.checkpoint(result.items.first())
            cache.clear()
            assertEquals(0, cache.count())
        } finally {
            cache.clear()
            cache.close()
        }
    }

    @Test
    fun copiesSimilarShotsAndAVerifiedCompressedCopy() = runBlocking {
        val now = System.currentTimeMillis()
        val original = scene(0)
        val a = insert("cleaner-a.jpg", original, now - 30_000)
        val b = insert("cleaner-b.jpg", original, now - 20_000)
        val c = insert("cleaner-c.jpg", scene(6), now - 10_000, captureMetadata = true)
        val inventory = photos.inventory()
        val ours = inventory.items.filter { it.id in setOf(a, b, c) }
        assertEquals(3, ours.size)
        assertTrue(
            ours.toString(),
            ours.all { it.bytes != null && it.revision.endsWith(":1200x900:1") },
        )

        val analysis = photos.analyze(ours) { _, _ -> }
        assertEquals(0, analysis.unavailable)
        val exact = analysis.groups.single()
        assertEquals(listOf(a, b).sorted(), exact.members.sorted())
        assertEquals(MatchKind.IDENTICAL_RESOURCES, exact.kind)

        val similar = photos.similar(analysis.items)
        assertTrue(
            "the nudged scene is suggested",
            similar.any { setOf(b, c) == it.members.toSet() },
        )
        assertTrue(
            similar.all {
                it.kind == MatchKind.SIMILAR && it.reason.startsWith("Nearby capture times")
            }
        )

        val item = analysis.items.first { it.id == c }
        assertEquals(
            java.time.Instant.ofEpochMilli(now - 10_000)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
            item.date,
        )
        val directory = File(context.cacheDir, "candidate-test-${UUID.randomUUID()}")
        val candidate =
            ImageCompression.makeCandidate(context, photos.originalStill(item, directory), directory)
        assertEquals(1200, candidate.assessment.output.width)
        assertTrue(
            candidate.assessment.issues.toString(),
            assessmentCanOfferCopy(candidate.assessment),
        )
        assertFalse(assessmentAuthorizesSourceDeletion(candidate.assessment))
        val saved = photos.saveCopy(candidate, item)
        added += Uri.parse(saved)
        val copy = photos.inventory().items.single { it.id == saved }
        assertEquals("cleaner-c compressed.jpg", copy.name)
        assertEquals(item.date, copy.date)
        val resized =
            ImageCompression.makeCandidate(
                context,
                candidate.source,
                directory,
                size = ImageSizeProfile.SMALL,
            )
        assertEquals(800, resized.assessment.output.width)
        assertEquals(600, resized.assessment.output.height)
        assertTrue(resized.assessment.issues.toString(), assessmentCanOfferCopy(resized.assessment))
        val resizedID = photos.saveCopy(resized, item)
        added += Uri.parse(resizedID)
        val resizedCopy = photos.inventory().items.single { it.id == resizedID }
        assertEquals(item.date, resizedCopy.date)
        assertEquals(800, resizedCopy.width)
        assertEquals(600, resizedCopy.height)
        assertTrue(
            photos.inventory().items.any { it.id == item.id && it.revision == item.revision }
        )
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun theRemovalGuardRefusesWhatWasNotReviewed() = runBlocking {
        val id = insert("cleaner-guard.jpg", scene(0), System.currentTimeMillis())
        val item = photos.inventory().items.single { it.id == id }
        val unreviewed = newReviewState()
        val refused = runCatching { photos.trashRequest(setOf(id), unreviewed) }.exceptionOrNull()
        assertEquals(
            "Selection changed, is protected, or needs review again.",
            (refused as CleanerException.Blocked).reason,
        )
        val reviewed =
            reviewStateDecide(
                unreviewed,
                mapOf(id to ReviewDecision.REMOVE),
                mapOf(id to item.revision),
            )
        assertNotNull(photos.trashRequest(setOf(id), reviewed))
        val stale =
            reviewStateDecide(
                unreviewed,
                mapOf(id to ReviewDecision.REMOVE),
                mapOf(id to "an older revision"),
            )
        assertTrue(runCatching { photos.trashRequest(setOf(id), stale) }.isFailure)
        Unit
    }

    @Test
    fun aMissingReviewFileStartsEmptyAndACorruptOneIsKept() = runBlocking {
        val file = File(context.cacheDir, "review-${UUID.randomUUID()}.json")
        val store = ReviewStore(file)
        assertTrue(store.read().decisions.isEmpty())
        store.decide(mapOf("x" to ReviewDecision.KEEP), mapOf("x" to "1"))
        assertEquals(ReviewDecision.KEEP, ReviewStore(file).read().decisions["x"])
        file.writeText("{broken")
        assertTrue(
            runCatching { store.decide(mapOf("y" to ReviewDecision.KEEP), emptyMap()) }
                .exceptionOrNull() is CleanerException.Unreadable
        )
        assertEquals("{broken", file.readText())
        file.delete()
        Unit
    }
}
