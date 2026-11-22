package gg.seven.cleaner

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import gg.seven.cleaner.core.AuxiliaryImage
import gg.seven.cleaner.core.CompressionAssessment
import gg.seven.cleaner.core.ImageFacts
import gg.seven.cleaner.core.ImageSizeProfile
import gg.seven.cleaner.core.StillColorSpace
import gg.seven.cleaner.core.TileError
import gg.seven.cleaner.core.auxiliaryDataRefusal
import gg.seven.cleaner.core.checkAuxiliaryImages
import gg.seven.cleaner.core.checkCompressionPixels
import gg.seven.cleaner.core.checkCompressionSource
import gg.seven.cleaner.core.checkHeicPixels
import gg.seven.cleaner.core.checkStillCopySpace
import gg.seven.cleaner.core.imageResizePlan
import gg.seven.cleaner.core.newCompressionAssessment
import gg.seven.cleaner.core.psnrScores
import gg.seven.cleaner.core.stillOutputType
import gg.seven.cleaner.core.tileError
import java.io.File
import java.io.RandomAccessFile
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
 * The measured still profiles (iOS ImageCompression): ordinary 8-bit sRGB JPEG/PNG re-encoded as
 * JPEG, and 8-bit sRGB HEIC re-encoded as HEIC (HeifWriter), never replacing the source. The
 * platform reads the facts; the core's profile gates decide, and its assessment scores the saved
 * output, not the encoder's intent. Android's HEIC encoder writes sRGB pixels and no auxiliary
 * images, so Display P3, gain-map, depth and matte HEICs are refused rather than flattened.
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
        context: Context,
        source: File,
        directory: File,
        quality: Double = 0.96,
        size: ImageSizeProfile = ImageSizeProfile.ORIGINAL,
    ): CompressionCandidate {
        var generated: CompressionCandidate? = null
        try {
            withContext(Dispatchers.Default) {
                generated = makeCandidateOnWorker(context.resources, source, directory, quality, size)
            }
            return requireNotNull(generated)
        } catch (failure: Throwable) {
            // Cancellation can arrive while dispatching the completed result back to the UI.
            generated?.output?.delete()
            throw failure
        }
    }

    private suspend fun makeCandidateOnWorker(
        res: Resources,
        source: File,
        directory: File,
        quality: Double,
        size: ImageSizeProfile,
    ): CompressionCandidate {
        coroutineContext.ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val uti = typeIdentifier(bounds.outMimeType)
        val heic = stillOutputType(uti) == "public.heic"
        val heif = if (heic) heifFacts(source) else null
        checkCompressionSource(
            quality,
            heif?.images ?: frameCount(source, uti),
            uti,
            bounds.outWidth.toLong(),
            bounds.outHeight.toLong(),
        )
        val exif = runCatching { ExifInterface(source) }.getOrNull()
        val xmp = exif?.getAttribute(ExifInterface.TAG_XMP).orEmpty()
        val xmpAuxiliary = auxiliaryXmp.any { it in xmp }
        if (heic) {
            // Nothing auxiliary can be carried: name what is there and keep the original.
            val present =
                heifAuxiliary(source) + if (xmpAuxiliary) listOf(AuxiliaryImage.UNIDENTIFIED) else emptyList()
            checkAuxiliaryImages(present, carried = emptyList())
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
            if (orientation in mirrored)
                throw blocked(
                    res.getString(R.string.compress_mirrored)
                )
        } else if (xmpAuxiliary) throw auxiliaryDataRefusal()
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        val original =
            BitmapFactory.decodeFile(source.path, options)
                ?: throw gg.seven.cleaner.core.CleanerException.Unreadable()
        val output = File(directory, "${UUID.randomUUID()}.${if (heic) "heic" else "jpg"}")
        var committed = false
        var reference: Bitmap = original
        try {
            if (Build.VERSION.SDK_INT >= 34 && original.hasGainmap())
                if (heic) checkAuxiliaryImages(listOf(AuxiliaryImage.ISO_GAIN_MAP), emptyList())
                else throw auxiliaryDataRefusal()
            if (heic)
                checkHeicPixels(
                    bitsPerComponent = bits(original),
                    colorSpace = stillColorSpace(original.colorSpace),
                    hasAlpha = original.hasAlpha(),
                    keepsDisplayP3 = false,
                )
            else
                checkCompressionPixels(
                    bitsPerComponent = bits(original),
                    isSrgb = original.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB),
                    hasAlpha = original.hasAlpha(),
                )
            val plan = imageResizePlan(original.width.toUInt(), original.height.toUInt(), size)
            directory.mkdirs()
            // Room for the candidate and its saved read-back before encoding (video does the same).
            checkStillCopySpace(
                plan.width,
                plan.height,
                runCatching { android.os.StatFs(directory.path).availableBytes }
                    .getOrDefault(0L)
                    .coerceAtLeast(0)
                    .toULong(),
            )
            if (plan.resized) reference = original.scale(plan.width.toInt(), plan.height.toInt())
            if (heic) {
                // HeifWriter takes the EXIF block whole; ExifInterface can't write HEIF, so the
                // capture tags are written into a JPEG carrier and its block is moved across.
                val block = exif?.let { exifBlock(it, directory) }
                encodeHeic(res, reference, output, quality, heif?.rotation ?: 0, block)
            } else {
                val encoded =
                    output.outputStream().use {
                        reference.compress(
                            Bitmap.CompressFormat.JPEG,
                            kotlin.math.round(quality * 100).toInt(),
                            it,
                        )
                    }
                if (!encoded)
                    throw blocked(res.getString(R.string.compress_encoder_failed))
            }
            coroutineContext.ensureActive()
            // The JPEG encoder writes pixels only; the capture metadata is copied across, then
            // checked. HEIC copies already carry it.
            if (exif != null && !heic) {
                val copy = ExifInterface(output)
                captureTags.forEach { tag ->
                    exif.getAttribute(tag)?.let { copy.setAttribute(tag, it) }
                }
                copy.saveAttributes()
            }
            val saved =
                BitmapFactory.decodeFile(output.path, options)
                    ?: throw blocked(res.getString(R.string.compress_reopen_failed))
            val savedExif = ExifInterface(output)
            val before = facts(original, exif, source)
            val after =
                try {
                    facts(saved, savedExif, output)
                } finally {
                    saved.recycle()
                }
            if (reference.width != after.width || reference.height != after.height)
                throw blocked(res.getString(R.string.compress_dimensions_changed))
            val scores = compare(res, reference, output)
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
                        assessment.issues =
                            assessment.issues + res.getString(R.string.compress_metadata_changed, tag)
                }
            if (heic) {
                // The HEIF rotation (irot) must match too, and nothing auxiliary may appear.
                val saved = heifFacts(output)
                if (saved.rotation != heif?.rotation)
                    assessment.issues = assessment.issues + res.getString(R.string.compress_orientation_changed)
                if (heifAuxiliary(output).isNotEmpty())
                    assessment.issues = assessment.issues + res.getString(R.string.compress_unexpected_auxiliary)
            }
            committed = true
            return CompressionCandidate(source, output, assessment, quality, size)
        } finally {
            if (reference !== original) reference.recycle()
            original.recycle()
            if (!committed) output.delete()
        }
    }

    /** EXIF orientations that flip the image; HeifWriter records rotation only. */
    private val mirrored = setOf(2, 4, 5, 7)

    internal data class HeifFacts(val images: UInt, val rotation: Int)

    /** Image count and HEIF rotation (`irot`) as the platform's HEIF reader reports them. */
    internal fun heifFacts(file: File): HeifFacts {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            HeifFacts(
                images =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_COUNT)
                        ?.toUIntOrNull() ?: 0u,
                rotation =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_ROTATION)
                        ?.toIntOrNull() ?: 0,
            )
        } catch (_: Exception) {
            HeifFacts(0u, 0)
        } finally {
            retriever.release()
        }
    }

    /** Auxiliary-image names (`auxC` type URNs) and the ISO gain map item type, by kind. */
    private val auxiliaryNames =
        listOf(
            "hdrgainmap" to AuxiliaryImage.HDR_GAIN_MAP,
            "portraiteffectsmatte" to AuxiliaryImage.PORTRAIT_MATTE,
            "skinmatte" to AuxiliaryImage.SKIN_MATTE,
            "hairmatte" to AuxiliaryImage.HAIR_MATTE,
            "teethmatte" to AuxiliaryImage.TEETH_MATTE,
            "glassesmatte" to AuxiliaryImage.GLASSES_MATTE,
            "skymatte" to AuxiliaryImage.SKY_MATTE,
            "disparity" to AuxiliaryImage.DISPARITY,
            "depth" to AuxiliaryImage.DEPTH,
            "auxid:2" to AuxiliaryImage.DEPTH,
        )

    /**
     * The auxiliary images a HEIF declares: its top-level `meta` box is read (bounded) and checked
     * for auxiliary-type properties (`auxC`), auxiliary references (`auxl`) and ISO gain-map items
     * (`tmap`). Android has no reader for these, and this only detects; a false match refuses, it
     * never drops data. A file without a readable `meta` box counts as unidentified.
     */
    internal fun heifAuxiliary(file: File): List<AuxiliaryImage> {
        val meta = runCatching { metaBox(file) }.getOrNull() ?: return listOf(AuxiliaryImage.UNIDENTIFIED)
        val text = String(meta, Charsets.ISO_8859_1)
        val found = mutableListOf<AuxiliaryImage>()
        if ("tmap" in text) found += AuxiliaryImage.ISO_GAIN_MAP
        if ("auxC" in text || "auxl" in text) {
            val lower = text.lowercase()
            val named = auxiliaryNames.filter { it.first in lower }.map { it.second }
            found += named.ifEmpty { listOf(AuxiliaryImage.UNIDENTIFIED) }
        }
        return found.distinct()
    }

    private fun metaBox(file: File): ByteArray? =
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            var offset = 0L
            while (offset + 8 <= length) {
                input.seek(offset)
                var size = input.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                var header = 8L
                if (size == 1L) {
                    size = input.readLong()
                    header = 16
                } else if (size == 0L) size = length - offset
                if (size < header || offset + size > length) return null
                if (type == "meta") {
                    if (size - header > MAX_META) return null
                    return ByteArray((size - header).toInt()).also { input.readFully(it) }
                }
                offset += size
            }
            null
        }

    /**
     * The source's capture tags as an EXIF block ("Exif\0\0" and the TIFF structure) for
     * HeifWriter: ExifInterface writes them into a one-pixel JPEG carrier, whose APP1 segment is
     * the block. The same tags the JPEG path copies, compared the same way after saving.
     */
    internal fun exifBlock(exif: ExifInterface, directory: File): ByteArray? {
        val carrier = File(directory, "${UUID.randomUUID()}.exif.jpg")
        try {
            val pixel = createBitmap(1, 1)
            try {
                carrier.outputStream().use { pixel.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally {
                pixel.recycle()
            }
            val copy = ExifInterface(carrier)
            captureTags.forEach { tag -> exif.getAttribute(tag)?.let { copy.setAttribute(tag, it) } }
            copy.saveAttributes()
            return app1Exif(carrier.readBytes())
        } finally {
            carrier.delete()
        }
    }

    /** The APP1 EXIF payload of a JPEG, from "Exif\0\0", or null when it has none. */
    internal fun app1Exif(jpeg: ByteArray): ByteArray? {
        val identifier = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            val start = i + 4
            val end = i + 2 + length
            if (length < 2 || end > jpeg.size) break
            if (
                marker == 0xE1 &&
                    end - start >= identifier.size &&
                    jpeg.copyOfRange(start, start + identifier.size).contentEquals(identifier)
            )
                return jpeg.copyOfRange(start, end)
            i = end
        }
        return null
    }

    internal fun encodeHeic(
        res: Resources,
        bitmap: Bitmap,
        output: File,
        quality: Double,
        rotation: Int,
        exif: ByteArray?,
    ) {
        val writer =
            try {
                HeifWriter.Builder(
                        output.path,
                        bitmap.width,
                        bitmap.height,
                        HeifWriter.INPUT_MODE_BITMAP,
                    )
                    .setQuality(kotlin.math.round(quality * 100).toInt())
                    .setRotation(rotation)
                    .setMaxImages(1)
                    .build()
            } catch (_: Exception) {
                throw blocked(res.getString(R.string.compress_no_heic_encoder))
            }
        // Closed through AutoCloseable: the writer releases its codec, muxer and thread.
        writer.use {
            try {
                it.start()
                it.addBitmap(bitmap)
                exif?.let { block -> it.addExifData(0, block, 0, block.size) }
                it.stop(HEIC_TIMEOUT_MS)
            } catch (_: Exception) {
                throw blocked(res.getString(R.string.compress_encoder_failed))
            }
        }
    }

    private fun stillColorSpace(space: ColorSpace?): StillColorSpace =
        when (space) {
            ColorSpace.get(ColorSpace.Named.SRGB) -> StillColorSpace.SRGB
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> StillColorSpace.DISPLAY_P3
            else -> StillColorSpace.OTHER
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
    private suspend fun compare(
        res: Resources,
        original: Bitmap,
        output: File,
    ): gg.seven.cleaner.core.PsnrScores {
        @Suppress("DEPRECATION")
        val decoder =
            (if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(output.path)
            else BitmapRegionDecoder.newInstance(output.path, false))
                ?: throw blocked(res.getString(R.string.compress_compare_failed))
        val tiles = mutableListOf<TileError>()
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        try {
            for (y in 0 until original.height step TILE) {
                coroutineContext.ensureActive()
                val h = minOf(TILE, original.height - y)
                val strip =
                    decoder.decodeRegion(Rect(0, y, original.width, y + h), options)
                        ?: throw blocked(res.getString(R.string.compress_compare_failed))
                try {
                    if (strip.width != original.width || strip.height != h)
                        throw blocked(res.getString(R.string.compress_compare_failed))
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
    private const val MAX_META = 16L * 1024 * 1024
    private const val HEIC_TIMEOUT_MS = 120_000L
}
