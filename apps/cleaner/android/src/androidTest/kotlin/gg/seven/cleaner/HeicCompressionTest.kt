package gg.seven.cleaner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.cleaner.core.CleanerException
import gg.seven.cleaner.core.ImageSizeProfile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HEIC copies through HeifWriter: the fixture is a HEIC written on the device with capture tags and
 * a 90° rotation; the copy must stay HEIC, keep the tags and rotation, and leave the original.
 */
class HeicCompressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(folder: File): File {
        val bitmap = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until 960 step 4) {
            paint.color = Color.rgb(40 + y * 180 / 960, 120, 220 - y * 120 / 960)
            canvas.drawRect(0f, y.toFloat(), 1280f, y + 4f, paint)
        }
        paint.color = Color.WHITE
        paint.textSize = 48f
        canvas.drawText("HEIC fixture, fine strokes 0123456789", 40f, 480f, paint)
        val carrier = File(folder, "tags.jpg")
        carrier.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(carrier).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Sevens")
            setAttribute(ExifInterface.TAG_MODEL, "Fixture")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:22 09:10:11")
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setLatLong(9.74, 118.73)
            saveAttributes()
        }
        val source = File(folder, "source.heic")
        val block = ImageCompression.exifBlock(ExifInterface(carrier), folder)
        ImageCompression.encodeHeic(context.resources, bitmap, source, 1.0, 90, block)
        bitmap.recycle()
        return source
    }

    @Test
    fun heicCopyStaysHeicWithTagsRotationAndOriginal() = runBlocking {
        val folder = File(context.cacheDir, "heic-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source =
                try {
                    fixture(folder)
                } catch (refused: CleanerException) {
                    // No HEVC encoder on this device: the refusal is the behaviour under test.
                    assertTrue(refused.message.orEmpty().contains("HEIC"))
                    return@runBlocking
                }
            assertEquals(90, ImageCompression.heifFacts(source).rotation)
            assertEquals(emptyList<Any>(), ImageCompression.heifAuxiliary(source))
            val original = source.readBytes()
            for (size in listOf(ImageSizeProfile.ORIGINAL, ImageSizeProfile.SMALL)) {
                val candidate = ImageCompression.makeCandidate(context, source, folder, 0.92, size)
                assertEquals("heic", candidate.output.extension)
                val issues = candidate.assessment.issues
                assertFalse(issues.joinToString(), issues.any { "metadata" in it || "Orientation" in it || "auxiliary" in it })
                assertEquals(6, candidate.assessment.output.orientation)
                assertEquals(if (size == ImageSizeProfile.SMALL) 800 else 1280, candidate.assessment.output.width)
                assertEquals(90, ImageCompression.heifFacts(candidate.output).rotation)
                val saved = ExifInterface(candidate.output)
                assertEquals("Sevens", saved.getAttribute(ExifInterface.TAG_MAKE))
                assertEquals("2026:11:22 09:10:11", saved.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals(9.74, saved.latLong!![0], 1e-4)
                assertArrayEquals(original, source.readBytes())
                println(
                    "CLEANER_HEIC size=$size before=${candidate.assessment.source.bytes} after=${candidate.assessment.output.bytes} " +
                        "global=${candidate.assessment.fullResolutionPsnr} worst=${candidate.assessment.worstTilePsnr} issues=$issues"
                )
                candidate.output.delete()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun mirroredHeicIsRefusedNotFlipped() = runBlocking {
        val folder = File(context.cacheDir, "heic-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            val carrier = File(folder, "tags.jpg")
            carrier.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(carrier).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, "2")
                saveAttributes()
            }
            val source = File(folder, "mirrored.heic")
            try {
                ImageCompression.encodeHeic(context.resources, bitmap, source, 1.0, 0, ImageCompression.exifBlock(ExifInterface(carrier), folder))
            } catch (_: CleanerException) {
                return@runBlocking
            }
            try {
                ImageCompression.makeCandidate(context, source, folder)
                fail("A mirrored HEIC must be refused")
            } catch (refused: CleanerException) {
                assertTrue(refused.message.orEmpty().contains("mirrored"))
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
