package gg.seven.cleaner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/** The power-of-two subsample that keeps the longer side at or above [maxSide] (1 when unknown). */
internal fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    if (width <= 0 || height <= 0 || maxSide <= 0) return sample
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/**
 * A still decoded upright for display, at most about [maxSide] on its longer side. ImageDecoder
 * applies the EXIF orientation itself.
 */
internal object UprightImages {
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? =
        decoded(ImageDecoder.createSource(resolver, uri), maxSide)

    fun decode(file: File, maxSide: Int): Bitmap? = decoded(ImageDecoder.createSource(file), maxSide)

    private fun decoded(source: ImageDecoder.Source, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height, maxSide))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
}
