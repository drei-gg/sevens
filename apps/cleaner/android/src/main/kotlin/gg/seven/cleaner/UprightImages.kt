package gg.seven.cleaner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * An EXIF orientation as a clockwise rotation followed by an optional horizontal mirror, the order
 * a [Matrix] applies `setRotate` then `postScale(-1, 1)`.
 */
internal data class ExifTransform(val degrees: Int, val mirror: Boolean)

/** EXIF orientations 1–8 (TIFF 6.0); anything else, including undefined, is upright. */
internal fun exifTransform(orientation: Int): ExifTransform =
    when (orientation) {
        2 -> ExifTransform(0, true) // flip horizontal
        3 -> ExifTransform(180, false)
        4 -> ExifTransform(180, true) // flip vertical
        5 -> ExifTransform(90, true) // transpose
        6 -> ExifTransform(90, false)
        7 -> ExifTransform(270, true) // transverse
        8 -> ExifTransform(270, false)
        else -> ExifTransform(0, false)
    }

/** The power-of-two subsample that keeps the longer side at or above [maxSide] (1 when unknown). */
internal fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    if (width <= 0 || height <= 0 || maxSide <= 0) return sample
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/**
 * A still decoded upright for display, at most about [maxSide] on its longer side. Android 9+ uses
 * ImageDecoder, which applies the EXIF orientation itself; older versions decode with
 * BitmapFactory and rotate by the ExifInterface orientation.
 */
internal object UprightImages {
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) decoded(ImageDecoder.createSource(resolver, uri), maxSide)
        else legacy(maxSide) { resolver.openInputStream(uri) }

    fun decode(file: File, maxSide: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) decoded(ImageDecoder.createSource(file), maxSide)
        else legacy(maxSide) { file.inputStream() }

    @androidx.annotation.RequiresApi(28)
    private fun decoded(source: ImageDecoder.Source, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height, maxSide))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun legacy(maxSide: Int, open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decoding returns null by design; only a missing stream fails here.
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) ?: Unit } ?: return null
        val sample = sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
        val bitmap =
            open()?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } ?: return null
        val orientation =
            runCatching {
                    open()?.use {
                        ExifInterface(it)
                            .getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                    }
                }
                .getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return bitmap.upright(exifTransform(orientation))
    }

    private fun Bitmap.upright(transform: ExifTransform): Bitmap {
        if (transform.degrees == 0 && !transform.mirror) return this
        val matrix =
            Matrix().apply {
                setRotate(transform.degrees.toFloat())
                if (transform.mirror) postScale(-1f, 1f)
            }
        val result = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (result !== this) recycle()
        return result
    }
}
