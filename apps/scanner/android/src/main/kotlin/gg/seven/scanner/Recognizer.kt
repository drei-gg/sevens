package gg.seven.scanner

import android.content.Context
import android.graphics.Bitmap
import gg.seven.scanner.core.PdfRect
import java.io.Closeable
import java.io.File
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract.PSM_AUTO
import org.bytedeco.tesseract.global.tesseract.RIL_TEXTLINE
import org.bytedeco.tesseract.global.tesseract.TessDeleteText

/** One recognized line and its bounds, normalized to the image with the origin at the bottom left (as Vision reports them). */
data class TextLine(val text: String, val bounds: PdfRect)

/**
 * On-device text recognition (iOS Vision's `VNRecognizeTextRequest`): Tesseract 5 with its
 * English LSTM model, bundled. Nothing leaves the device. One recognizer per batch of pages.
 */
class Recognizer private constructor(private val api: TessBaseAPI) : Closeable {
    /** The page's lines, top to bottom. */
    fun lines(bitmap: Bitmap): List<TextLine> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = ByteArray(w * h) { i ->
            val p = pixels[i]
            ((((p shr 16) and 0xff) * 54 + ((p shr 8) and 0xff) * 183 + (p and 0xff) * 19) shr 8).toByte()
        }
        api.SetImage(gray, w, h, 1, w)
        if (api.Recognize(null) != 0) throw IllegalStateException("Recognition failed")
        val lines = mutableListOf<TextLine>()
        val iterator = api.GetIterator() ?: return lines
        try {
            iterator.Begin()
            val box = Array(4) { IntArray(1) }
            do {
                val pointer: BytePointer = iterator.GetUTF8Text(RIL_TEXTLINE) ?: continue
                val text = pointer.string.trim()
                TessDeleteText(pointer)
                if (text.isEmpty() || !iterator.BoundingBox(RIL_TEXTLINE, box[0], box[1], box[2], box[3])) continue
                val (left, top, right, bottom) = box.map { it[0] }
                lines += TextLine(
                    text,
                    PdfRect(left.toDouble() / w, 1.0 - bottom.toDouble() / h, (right - left).toDouble() / w, (bottom - top).toDouble() / h),
                )
            } while (iterator.Next(RIL_TEXTLINE))
        } finally {
            iterator.deallocate()
            api.Clear()
        }
        return lines
    }

    /** The page's text, one recognized line per line. */
    fun text(bitmap: Bitmap): String = lines(bitmap).joinToString("\n") { it.text }

    override fun close() {
        api.End()
        api.deallocate()
    }

    companion object {
        private const val MODEL = "tessdata/eng.traineddata"

        /** Copies the bundled model out of the APK once, then starts Tesseract. */
        @Synchronized
        fun open(context: Context): Recognizer {
            val root = File(context.noBackupFilesDir, "ocr")
            val model = File(root, MODEL)
            val size = context.assets.openFd(MODEL).use { it.length }
            if (model.length() != size) {
                model.parentFile?.mkdirs()
                val partial = File(model.path + ".partial")
                context.assets.open(MODEL).use { input -> partial.outputStream().use { input.copyTo(it) } }
                if (!partial.renameTo(model)) throw IllegalStateException("Could not prepare text recognition")
            }
            val api = TessBaseAPI()
            // Tesseract 5 takes the tessdata directory itself (4.0 and later), not its parent.
            if (api.Init(model.parentFile!!.path + "/", "eng") != 0) {
                api.deallocate()
                throw IllegalStateException("Could not start text recognition")
            }
            api.SetPageSegMode(PSM_AUTO)
            api.SetVariable("user_defined_dpi", "300")
            return Recognizer(api)
        }
    }
}
