package gg.seven.scanner

import android.content.Context
import android.graphics.Bitmap
import gg.seven.ocr.OnDeviceTextRecognizer
import gg.seven.scanner.core.PdfRect
import java.io.Closeable

data class TextLine(val text: String, val bounds: PdfRect)

/** Scanner maps the shared native recognizer's image coordinates into its PDF geometry. */
class Recognizer private constructor(private val native: OnDeviceTextRecognizer) : Closeable {
    fun lines(bitmap: Bitmap): List<TextLine> =
        native.lines(bitmap).map {
            TextLine(it.text, PdfRect(it.bounds.x, it.bounds.y, it.bounds.width, it.bounds.height))
        }

    fun text(bitmap: Bitmap): String = native.text(bitmap)

    override fun close() = native.close()

    companion object {
        fun open(context: Context) = Recognizer(OnDeviceTextRecognizer.open(context))
    }
}
