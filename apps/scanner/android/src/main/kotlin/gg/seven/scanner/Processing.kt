package gg.seven.scanner

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import gg.seven.scanner.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/*
 * Pixels and PDFs (port of iOS Processing.swift). Every operation decodes one page at a time; the
 * sizes, paper boxes, frames and qualities come from the core's page geometry. Stored originals
 * are never changed: adjustments are applied whenever a page is shown, recognized or exported.
 */
object Processing {
    private val geometry = pageProcessing()

    // MARK: decoding and adjustments

    /** The image at [file] (or any stream), upright per its EXIF, at most [maxPixels] on its longest side. */
    fun decode(open: () -> InputStream, maxPixels: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ScanException.MissingPage()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
        val decoded =
            open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw ScanException.MissingPage()
        val orientation = runCatching { open().use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        val scale = minOf(1f, maxPixels.toFloat() / maxOf(decoded.width, decoded.height))
        if (orientation == 0 && scale >= 1f) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(orientation.toFloat())
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    fun decode(file: File, maxPixels: Int): Bitmap = decode({ file.inputStream() }, maxPixels)

    /**
     * The page as it is shown, recognized and exported: quarter turns clockwise, then Core Image's
     * CIColorControls (saturation, brightness, contrast), on an opaque white ground.
     */
    fun rendered(file: File, settings: PageSettings, maxPixels: Int = geometry.renderMaxPixels.toInt()): Bitmap {
        pageSettingsValidate(settings)
        val source = decode(file, maxPixels)
        if (settings == pageSettingsDefault()) return source
        val turns = settings.quarterTurns.toInt()
        val (w, h) = if (turns % 2 == 0) source.width to source.height else source.height to source.width
        val out = createBitmap(w, h)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            val matrix = Matrix().apply {
                postRotate(90f * turns, source.width / 2f, source.height / 2f)
                postTranslate((w - source.width) / 2f, (h - source.height) / 2f)
            }
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(ColorControls.matrix(settings.contrast.toFloat(), settings.brightness.toFloat(), settings.grayscale))) })
        }
        source.recycle()
        return out
    }

    fun jpeg(bitmap: Bitmap, quality: Double = geometry.pageJpegQuality): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), it) }.toByteArray()

    /**
     * A captured or imported image as a stored page: fitted inside 2400 pixels, drawn on white,
     * JPEG 0.85, written to [out].
     */
    fun normalize(bitmap: Bitmap, out: File) {
        val size = normalizedPageSize(bitmap.width.toDouble(), bitmap.height.toDouble()) ?: throw ScanException.InvalidDocument()
        val page = createBitmap(size.width.toInt(), size.height.toInt())
        Canvas(page).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, null, android.graphics.Rect(0, 0, page.width, page.height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        out.writeBytes(jpeg(page))
        page.recycle()
    }

    // MARK: import

    /**
     * Files may be images or PDFs. PDFs are flattened to page images (editable objects and the
     * container are not kept); encrypted PDFs are refused. Originals outside the app are untouched.
     * Prepared pages land in [staging].
     */
    suspend fun importFiles(context: Context, uris: List<Uri>, staging: File): List<File> {
        val limits = scanLimits()
        if (uris.isEmpty() || uris.size > limits.maxPages) throw ScanException.TooLarge()
        val resolver = context.contentResolver
        val pages = mutableListOf<File>()
        var total = 0L
        fun add(file: File) {
            total += file.length()
            if (total > limits.maxDocumentImageBytes) throw ScanException.TooLarge()
            pages += file
        }
        for (uri in uris) {
            coroutineContext.ensureActive()
            if (size(context, uri) > geometry.maxImportFileBytes) throw ScanException.TooLarge()
            val type = resolver.getType(uri).orEmpty()
            val isPdf = type == "application/pdf" || displayName(context, uri).lowercase().endsWith(".pdf")
            if (isPdf) {
                val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw ScanException.InvalidDocument()
                val renderer =
                    try {
                        PdfRenderer(descriptor)
                    } catch (_: SecurityException) {
                        descriptor.close()
                        throw ScanException.InvalidDocument() // password-protected
                    } catch (_: Exception) {
                        descriptor.close()
                        throw ScanException.InvalidDocument()
                    }
                renderer.use {
                    if (it.pageCount == 0 || pages.size + it.pageCount > limits.maxPages) throw ScanException.InvalidDocument()
                    for (index in 0 until it.pageCount) {
                        coroutineContext.ensureActive()
                        it.openPage(index).use { page ->
                            val size = pdfImportRasterSize(page.width.toDouble(), page.height.toDouble()) ?: throw ScanException.InvalidDocument()
                            val bitmap = createBitmap(size.width.toInt(), size.height.toInt())
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val file = File(staging, "${UUID.randomUUID()}.jpg").apply { writeBytes(jpeg(bitmap)) }
                            bitmap.recycle()
                            add(file)
                        }
                    }
                }
            } else {
                if (pages.size >= limits.maxPages) throw ScanException.TooLarge()
                val bitmap = decode({ resolver.openInputStream(uri) ?: throw ScanException.MissingPage() }, geometry.renderMaxPixels.toInt())
                val file = File(staging, "${UUID.randomUUID()}.jpg")
                normalize(bitmap, file)
                bitmap.recycle()
                add(file)
            }
        }
        return pages
    }

    private fun size(context: Context, uri: Uri): Long =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use(ParcelFileDescriptor::getStatSize) }.getOrNull()?.takeIf { it >= 0 }
            ?: runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
                }
                .getOrNull()
            ?: 0L

    fun displayName(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }
            .getOrNull()
            ?: uri.lastPathSegment.orEmpty()

    // MARK: PDF export

    /**
     * The selected pages, one per sheet of [paper], each fitted inside an 18-point margin. With
     * [searchable], the page is recognized as rendered and each line is laid over it as invisible
     * Helvetica (text render mode 3), scaled to the line's bounds, so the PDF can be searched and
     * selected. Written to [out]; a failure removes the partial file.
     */
    suspend fun pdf(
        context: Context,
        files: List<File>,
        pages: List<ScanPage>,
        out: File,
        quality: PdfQuality = PdfQuality.STANDARD,
        paper: PaperSize = PaperSize.A4,
        searchable: Boolean = true,
    ) {
        if (pages.isEmpty() || files.size != pages.size || pages.size > scanLimits().maxPages) throw ScanException.InvalidDocument()
        PDFBoxResourceLoader.init(context.applicationContext)
        val render = pdfRender(quality)
        val sheet = paperBox(paper)
        val ocr = if (searchable) Recognizer.open(context) else null
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val font = PDType1Font.HELVETICA
                for ((file, page) in files.zip(pages)) {
                    coroutineContext.ensureActive()
                    val bitmap = rendered(file, page.settings, render.maxPixels.toInt())
                    val image = JPEGFactory.createFromByteArray(document, jpeg(bitmap, render.jpegQuality))
                    val frame = pdfImageFrame(paper, image.width.toLong(), image.height.toLong()) ?: throw ScanException.InvalidDocument()
                    val sheetPage = PDPage(PDRectangle(sheet.width.toFloat(), sheet.height.toFloat()))
                    document.addPage(sheetPage)
                    PDPageContentStream(document, sheetPage).use { stream ->
                        stream.drawImage(image, frame.x.toFloat(), frame.y.toFloat(), frame.width.toFloat(), frame.height.toFloat())
                        ocr?.lines(bitmap)?.forEach { line -> invisibleLine(stream, font, line, frame) }
                    }
                    bitmap.recycle()
                }
                document.save(out)
            }
        } catch (error: Throwable) {
            out.delete()
            throw error
        } finally {
            ocr?.close()
        }
    }

    private fun invisibleLine(stream: PDPageContentStream, font: PDType1Font, line: TextLine, frame: PdfRect) {
        val text = line.text.map { c -> if (runCatching { font.encode(c.toString()) }.isSuccess) c else ' ' }.joinToString("").trimEnd()
        if (text.isBlank()) return
        val rect = pdfTextRect(frame, line.bounds)
        val size = 12f
        val width = font.getStringWidth(text) / 1000f * size
        val ascent = HELVETICA_ASCENT * size
        val descent = HELVETICA_DESCENT * size
        if (width <= 0f || ascent + descent <= 0f) return
        val sx = rect.width.toFloat() / width
        val sy = rect.height.toFloat() / (ascent + descent)
        stream.beginText()
        stream.setRenderingMode(RenderingMode.NEITHER)
        stream.setFont(font, size)
        stream.setTextMatrix(PdfMatrix(sx, 0f, 0f, sy, rect.x.toFloat(), rect.y.toFloat() + descent * sy))
        stream.showText(text)
        stream.endText()
    }

    // Helvetica's ascender and descender, per point of size (its AFM: 718 and −207 per 1000).
    private const val HELVETICA_ASCENT = 0.718f
    private const val HELVETICA_DESCENT = 0.207f

    // MARK: export folders

    /** A fresh folder for one export; cleaned on a later launch once it is a day old. */
    fun exportFile(context: Context, filename: String): File {
        val folder = File(File(context.cacheDir, EXPORTS), UUID.randomUUID().toString().uppercase())
        folder.mkdirs()
        return File(folder, filename)
    }

    /** App-owned export folders older than 24 hours, so share targets have time to finish reading. */
    fun cleanOldExports(context: Context, now: Long = System.currentTimeMillis()) {
        val base = File(context.cacheDir, EXPORTS)
        for (folder in base.listFiles().orEmpty()) {
            if (runCatching { UUID.fromString(folder.name) }.isFailure) continue
            if (now - folder.lastModified() > 86_400_000L) folder.deleteRecursively()
        }
    }

    const val EXPORTS = "ScannerExports"
}

/**
 * Core Image's CIColorControls as a 4×5 colour matrix on 0…255 channels: saturation mixes toward
 * Rec. 709 luma, then brightness adds, then contrast scales around mid-grey. Pure, so it is tested
 * on the JVM.
 */
object ColorControls {
    fun matrix(contrast: Float, brightness: Float, grayscale: Boolean): FloatArray {
        val s = if (grayscale) 0f else 1f
        val c = contrast
        val b = brightness * 255f
        val (lr, lg, lb) = Triple(0.2126f, 0.7152f, 0.0722f)
        val offset = c * b + 128f * (1 - c)
        fun row(r: Float, g: Float, bl: Float) = floatArrayOf(c * r, c * g, c * bl, 0f, offset)
        return row(lr * (1 - s) + s, lg * (1 - s), lb * (1 - s)) +
            row(lr * (1 - s), lg * (1 - s) + s, lb * (1 - s)) +
            row(lr * (1 - s), lg * (1 - s), lb * (1 - s) + s) +
            floatArrayOf(0f, 0f, 0f, 1f, 0f)
    }

    /** One pixel through [matrix], clamped as the platform does. */
    fun apply(matrix: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun ch(i: Int) = (matrix[i] * r + matrix[i + 1] * g + matrix[i + 2] * b + matrix[i + 4]).coerceIn(0f, 255f)
        return Triple(ch(0), ch(5), ch(10))
    }
}
