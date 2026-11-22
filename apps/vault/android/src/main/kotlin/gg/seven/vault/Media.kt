package gg.seven.vault

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import kotlin.math.max

/**
 * Decoding for thumbnails and previews: downsampled to `maxPixels` on the long side, upright
 * (ImageDecoder applies the EXIF orientation).
 */
object Images {
    fun decode(file: File, maxPixels: Int): Bitmap? =
        runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val long = max(info.size.width, info.size.height)
                    if (long > maxPixels) {
                        val scale = maxPixels.toFloat() / long
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            .getOrNull()
}
