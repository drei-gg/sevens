package gg.seven.cleaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import gg.seven.cleaner.core.CompressionAssessment
import gg.seven.cleaner.core.ImageFacts
import gg.seven.cleaner.core.ImageSizeProfile
import gg.seven.cleaner.core.TileError
import gg.seven.cleaner.core.auxiliaryDataRefusal
import gg.seven.cleaner.core.checkCompressionPixels
import gg.seven.cleaner.core.checkCompressionSource
import gg.seven.cleaner.core.imageResizePlan
import gg.seven.cleaner.core.newCompressionAssessment
import gg.seven.cleaner.core.psnrScores
import gg.seven.cleaner.core.tileError
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CompressionCandidate(
    val source: File,
    val output: File,
    val assessment: CompressionAssessment,
    val quality: Double,
    val size: ImageSizeProfile = ImageSizeProfile.ORIGINAL,
)

/**
 * The initial measured profile (iOS ImageCompression): ordinary 8-bit sRGB stills, re-encoded as
 * JPEG at the same dimensions, never replacing the source. The platform reads the facts; the core's
 * profile gates decide, and its assessment scores the saved output, not the encoder's intent.
 */
object ImageCompression {
    /** Capture metadata the copy claims to keep, compared value for value after saving. */
    internal val captureTags =
        listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        )

    /** XMP that marks depth maps, gain maps or embedded motion: data this profile can't keep. */
    private val auxiliaryXmp =
        listOf("GContainer", "GDepth:", "hdrgm:", "GCamera:MotionPhoto", "MicroVideo")

    suspend fun makeCandidate(
        source: File,
        directory: File,
        quality: Double = 0.96,
        size: ImageSizeProfile = ImageSizeProfile.ORIGINAL,
    ): CompressionCandidate {
        var generated: CompressionCandidate? = null
        try {
            withContext(Dispatchers.Default) {
                generated = makeCandidateOnWorker(source, directory, quality, size)
            }
            return requireNotNull(generated)
        } catch (failure: Throwable) {
            // Cancellation can arrive while dispatching the completed result back to the UI.
            generated?.output?.delete()
            throw failure
        }
    }

    private suspend fun makeCandidateOnWorker(
        source: File,
        directory: File,
        quality: Double,
        size: ImageSizeProfile,
    ): CompressionCandidate {
        coroutineContext.ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val uti = typeIdentifier(bounds.outMimeType)
        checkCompressionSource(
            quality,
            frameCount(source, uti),
            uti,
            bounds.outWidth.toLong(),
            bounds.outHeight.toLong(),
        )
        val exif = runCatching { ExifInterface(source) }.getOrNull()
        val xmp = exif?.getAttribute(ExifInterface.TAG_XMP).orEmpty()
        if (auxiliaryXmp.any { it in xmp }) throw auxiliaryDataRefusal()
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        val original =
            BitmapFactory.decodeFile(source.path, options)
                ?: throw gg.seven.cleaner.core.CleanerException.Unreadable()
        val output = File(directory, "${UUID.randomUUID()}.jpg")
        var committed = false
        var reference: Bitmap = original
        try {
            if (Build.VERSION.SDK_INT >= 34 && original.hasGainmap()) throw auxiliaryDataRefusal()
            checkCompressionPixels(
                bitsPerComponent = bits(original),
                isSrgb = original.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB),
                hasAlpha = original.hasAlpha(),
            )
            val plan = imageResizePlan(original.width.toUInt(), original.height.toUInt(), size)
            if (plan.resized) reference = original.scale(plan.width.toInt(), plan.height.toInt())
            directory.mkdirs()
            val encoded =
                output.outputStream().use {
                    reference.compress(
                        Bitmap.CompressFormat.JPEG,
                        kotlin.math.round(quality * 100).toInt(),
                        it,
                    )
                }
            if (!encoded) throw blocked("The encoder could not finish. The original is unchanged.")
            coroutineContext.ensureActive()
            // The encoder writes pixels only; the capture metadata is copied across, then checked.
            if (exif != null) {
                val copy = ExifInterface(output)
                captureTags.forEach { tag ->
                    exif.getAttribute(tag)?.let { copy.setAttribute(tag, it) }
                }
                copy.saveAttributes()
            }
            val saved =
                BitmapFactory.decodeFile(output.path, options)
                    ?: throw blocked("The saved candidate could not be reopened.")
            val savedExif = ExifInterface(output)
            val before = facts(original, exif, source)
            val after =
                try {
                    facts(saved, savedExif, output)
                } finally {
                    saved.recycle()
                }
            if (reference.width != after.width || reference.height != after.height)
                throw blocked("The output dimensions changed.")
            val scores = compare(reference, output)
            val assessment =
                newCompressionAssessment(
                    before,
                    after,
                    scores.fullResolution,
                    scores.worstTile,
                    allowResize = plan.resized,
                )
            if (exif != null)
                for (tag in captureTags) {
                    val expected = exif.getAttribute(tag) ?: continue
                    if (savedExif.getAttribute(tag) != expected)
                        assessment.issues = assessment.issues + "Capture metadata changed: $tag."
                }
            committed = true
            return CompressionCandidate(source, output, assessment, quality, size)
        } finally {
            if (reference !== original) reference.recycle()
            original.recycle()
            if (!committed) output.delete()
        }
    }

    private fun bits(bitmap: Bitmap): Int =
        when {
            bitmap.config == Bitmap.Config.RGBA_F16 -> 16
            Build.VERSION.SDK_INT >= 33 && bitmap.config == Bitmap.Config.RGBA_1010102 -> 10
            else -> 8
        }

    /** PNGs can be animated (an `acTL` chunk before the image data); JPEG stills are one frame. */
    internal fun frameCount(file: File, uti: String): UInt {
        if (uti != "public.png") return if (uti.isEmpty()) 0u else 1u
        file.inputStream().buffered().use { input ->
            val header = ByteArray(8)
            if (input.read(header) != 8) return 0u
            val length = ByteArray(4)
            val type = ByteArray(4)
            while (input.read(length) == 4 && input.read(type) == 4) {
                val name = String(type, Charsets.US_ASCII)
                if (name == "acTL") return 2u
                if (name == "IDAT") return 1u
                val size = ByteBuffer.wrap(length).int.toLong() + 4
                if (input.skip(size) != size) return 0u
            }
        }
        return 1u
    }

    private fun facts(bitmap: Bitmap, exif: ExifInterface?, file: File) =
        ImageFacts(
            width = bitmap.width,
            height = bitmap.height,
            orientation =
                exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)?.takeIf { it != 0 } ?: 1,
            colorSpace =
                if (bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)) "sRGB"
                else bitmap.colorSpace?.name ?: "unknown",
            hasAlpha = bitmap.hasAlpha(),
            bits = bits(bitmap),
            bytes = file.length(),
        )

    /**
     * Every pixel at native resolution: the saved output is read back in 128-row strips (bounded
     * memory), each 128-pixel tile turned into RGBX and scored by the core. The worst tile catches
     * localized damage a global average hides. A diagnostic, not perceptual approval.
     */
    private suspend fun compare(original: Bitmap, output: File): gg.seven.cleaner.core.PsnrScores {
        @Suppress("DEPRECATION")
        val decoder =
            (if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(output.path)
            else BitmapRegionDecoder.newInstance(output.path, false))
                ?: throw blocked("Could not compare full-resolution detail.")
        val tiles = mutableListOf<TileError>()
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        try {
            for (y in 0 until original.height step TILE) {
                coroutineContext.ensureActive()
                val h = minOf(TILE, original.height - y)
                val strip =
                    decoder.decodeRegion(Rect(0, y, original.width, y + h), options)
                        ?: throw blocked("Could not compare full-resolution detail.")
                try {
                    if (strip.width != original.width || strip.height != h)
                        throw blocked("Could not compare full-resolution detail.")
                    for (x in 0 until original.width step TILE) {
                        val w = minOf(TILE, original.width - x)
                        tiles += tileError(rgbx(original, x, y, w, h), rgbx(strip, x, 0, w, h))
                    }
                } finally {
                    strip.recycle()
                }
            }
        } finally {
            decoder.recycle()
        }
        return psnrScores(tiles)
    }

    private fun rgbx(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): ByteBuffer {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x, y, w, h)
        val buffer = ByteBuffer.allocateDirect(w * h * 4)
        for (p in pixels) {
            buffer.put((p shr 16 and 0xFF).toByte())
            buffer.put((p shr 8 and 0xFF).toByte())
            buffer.put((p and 0xFF).toByte())
            buffer.put(0)
        }
        buffer.flip()
        return buffer
    }

    private const val TILE = 128
}
