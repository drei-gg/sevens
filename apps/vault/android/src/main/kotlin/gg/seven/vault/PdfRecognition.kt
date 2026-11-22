package gg.seven.vault

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import gg.seven.ocr.OnDeviceTextRecognizer
import gg.seven.vault.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native PDF rendering, one bounded page at a time. No plaintext image files are written. */
object PdfRecognition {
    private fun open(file: File): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }

    suspend fun pageCount(file: File): UInt =
        withContext(Dispatchers.Default) { open(file).use { it.pageCount.toUInt() } }

    suspend fun read(
        context: Context,
        file: File,
        first: UInt,
        last: UInt,
        beforePage: suspend (UInt) -> Unit,
    ): String =
        withContext(Dispatchers.Default) {
            open(file).use { pdf ->
                val plan = recognitionPlan(pdf.pageCount.toUInt(), first, last)
                plan.issue?.let { throw VaultMessage(it.wording) }
                OnDeviceTextRecognizer.open(context).use { reader ->
                    var text = ""
                    for (index in plan.pages) {
                        beforePage(index + 1u)
                        pdf.openPage(index.toInt()).use { page ->
                            val size =
                                recognitionPageSize(page.width.toDouble(), page.height.toDouble())
                            val bitmap = createBitmap(size.width.toInt(), size.height.toInt())
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                                text = recognitionAppend(text, index + 1u, reader.text(bitmap))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    text
                }
            }
        }
}
