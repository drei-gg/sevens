package gg.seven.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import gg.seven.qr.core.BitMatrix
import gg.seven.qr.core.PaperSize
import gg.seven.qr.core.RgbColor
import gg.seven.qr.core.SheetGrid
import gg.seven.qr.core.paperSizePoints
import gg.seven.qr.core.sheetCells
import gg.seven.qr.core.sheetMargin
import gg.seven.qr.core.svg
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/* Make's export (SPEC §8): PNG at any size, SVG from the module grid, a PDF ready to print. */

fun RgbColor.argb(): Int =
    (0xFF shl 24) or ((red * 255).roundToInt().coerceIn(0, 255) shl 16) or ((green * 255).roundToInt().coerceIn(0, 255) shl 8) or (blue * 255).roundToInt().coerceIn(0, 255)

val BLACK = RgbColor(0.0, 0.0, 0.0)
val WHITE = RgbColor(1.0, 1.0, 1.0)

enum class MakeExportFormat(val title: String, val ext: String, val mime: String) {
    PNG("PNG image", "png", "image/png"),
    SVG("SVG (vector)", "svg", "image/svg+xml"),
    PDF("PDF", "pdf", "application/pdf"),
}

enum class PdfLayout { SINGLE, SHEET }

val SheetGrid.title
    get() =
        when (this) {
            SheetGrid.GRID2X3 -> "2 by 3 (6 labels)"
            SheetGrid.GRID3X4 -> "3 by 4 (12 labels)"
            SheetGrid.GRID4X6 -> "4 by 6 (24 labels)"
        }

val PaperSize.title
    get() =
        when (this) {
            PaperSize.A4 -> "A4"
            PaperSize.LETTER -> "US Letter"
        }

/** The export choices and the pure rules around them. */
data class MakeExportOptions(
    val format: MakeExportFormat = MakeExportFormat.PNG,
    /** 512, 1024, 2048, or null for a custom size. */
    val preset: Int? = 1024,
    val customSize: String = "1024",
    val pdfLayout: PdfLayout = PdfLayout.SINGLE,
    val grid: SheetGrid = SheetGrid.GRID3X4,
    val paper: PaperSize = gg.seven.qr.core.paperSizeForRegion(Locale.getDefault().country),
    val caption: String = "",
) {
    /** The preset, or the typed number clamped to 32…4096 (blank or not a number reads as 1024). */
    val pixelSize: Int
        get() = preset ?: clampedSize(customSize)

    val customSizeWasClamped: Boolean
        get() {
            if (preset != null) return false
            val n = customSize.trim().toIntOrNull() ?: return false
            return n != clampedSize(customSize)
        }

    val cleanCaption: String?
        get() = caption.trim().takeIf { it.isNotEmpty() }

    companion object {
        const val MIN_PIXEL_SIZE = 32
        val PRESETS = listOf(512, 1024, 2048)

        fun clampedSize(raw: String): Int {
            val digits = raw.trim()
            val n = digits.toIntOrNull() ?: return if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) PngRenderer.MAX_PIXEL_SIZE else 1024
            return n.coerceIn(MIN_PIXEL_SIZE, PngRenderer.MAX_PIXEL_SIZE)
        }

        /** `qr-link-20260311-1405.png`; sheets end `-sheet.pdf`. Fixed calendar and digits. */
        fun fileName(symbology: MakeSymbology, kind: MakeFormKind, at: LocalDateTime, format: MakeExportFormat, sheet: Boolean): String {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT).format(at)
            val suffix = if (sheet && format == MakeExportFormat.PDF) "-sheet" else ""
            return "${symbology.raw}-${kind.fileToken}-$stamp$suffix.${format.ext}"
        }
    }
}

object PngRenderer {
    const val MAX_PIXEL_SIZE = 4096

    /** Integer pixels per module: floor(pixelSize / columns), at least 1. */
    fun scale(columns: Int, pixelSize: Int): Int = if (columns <= 0) 1 else maxOf(1, pixelSize.coerceIn(1, MAX_PIXEL_SIZE) / columns)

    /** Opaque, a whole number of pixels per module, so it can be a little under [pixelSize]. */
    fun bitmap(matrix: BitMatrix, pixelSize: Int, foreground: RgbColor, background: RgbColor): Bitmap? {
        if (matrix.width <= 0 || matrix.height <= 0) return null
        val s = scale(matrix.width.toInt(), pixelSize)
        val rows = MakeGeometry.rows(matrix)
        val bitmap = Bitmap.createBitmap(matrix.width.toInt() * s, rows * s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background.argb())
        val paint = Paint().apply {
            color = foreground.argb()
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        for (run in MakeGeometry.runs(matrix)) {
            canvas.drawRect((run.x * s).toFloat(), (run.row * s).toFloat(), ((run.x + run.length) * s).toFloat(), ((run.row + 1) * s).toFloat(), paint)
        }
        return bitmap
    }

    fun png(matrix: BitMatrix, pixelSize: Int, foreground: RgbColor, background: RgbColor): ByteArray {
        val bitmap = bitmap(matrix, pixelSize, foreground, background) ?: return ByteArray(0)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }
}

object SvgRenderer {
    /** UTF-8 SVG from the core's writer; one-row codes are stretched to the bar height first. */
    fun data(matrix: BitMatrix, moduleSize: Int, foreground: RgbColor, background: RgbColor?): ByteArray =
        svg(MakeGeometry.stretched(matrix), moduleSize.toLong(), foreground, background).toByteArray()
}

object PdfRenderer {
    /** Caption sizes are the one fixed font size allowed (points). */
    private const val SINGLE_CAPTION = 12f
    private const val SHEET_CAPTION = 8f

    private fun document(paper: PaperSize, draw: (Canvas) -> Unit): ByteArray {
        val size = paperSizePoints(paper)
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(size.width.roundToInt(), size.height.roundToInt(), 1).create())
            draw(page.canvas)
            doc.finishPage(page)
            return ByteArrayOutputStream().use {
                doc.writeTo(it)
                it.toByteArray()
            }
        } finally {
            doc.close()
        }
    }

    /** One code centred on the page inside the margin, its caption under it. */
    fun single(matrix: BitMatrix, caption: String?, foreground: RgbColor, background: RgbColor, paper: PaperSize): ByteArray {
        val size = paperSizePoints(paper)
        val margin = sheetMargin().toFloat()
        val areaW = size.width.toFloat() - 2 * margin
        val areaH = size.height.toFloat() - 2 * margin
        val side = minOf(areaW, areaH * 0.8f)
        val left = margin + areaW / 2 - side / 2
        val top = margin + areaH / 2 - side / 2 - 10
        return document(paper) { draw(it, matrix, left, top, side, side, caption, SINGLE_CAPTION, foreground, background) }
    }

    /** A one-page label sheet: the same code in every cell of the core's layout, scaled to fit. */
    fun sheet(matrix: BitMatrix, caption: String?, grid: SheetGrid, paper: PaperSize, foreground: RgbColor, background: RgbColor): ByteArray =
        document(paper) { canvas ->
            sheetCells(grid, paper).forEach { cell ->
                draw(canvas, matrix, cell.x.toFloat(), cell.y.toFloat(), cell.width.toFloat(), cell.height.toFloat(), caption, SHEET_CAPTION, foreground, background)
            }
        }

    /** The code scaled to fit the box, centred horizontally and kept at the top, the caption below. */
    private fun draw(canvas: Canvas, matrix: BitMatrix, x: Float, y: Float, w: Float, h: Float, caption: String?, captionSize: Float, foreground: RgbColor, background: RgbColor) {
        if (matrix.width <= 0 || matrix.height <= 0) return
        val rows = MakeGeometry.rows(matrix)
        val text = caption?.trim()?.takeIf { it.isNotEmpty() }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = captionSize
            color = 0xFF000000.toInt()
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        val lineHeight = ceil(paint.fontSpacing)
        val captionHeight = if (text != null) lineHeight + 4 else 0f
        val availH = maxOf(1f, h - captionHeight)
        var module = minOf(w / matrix.width, availH / rows)
        if (module >= 1) module = floor(module)
        val cw = module * matrix.width
        val ch = module * rows
        var ox = x + (w - cw) / 2
        var oy = y + if (text != null) 0f else (availH - ch) / 2
        if (module >= 1) {
            ox = ox.roundToInt().toFloat()
            oy = oy.roundToInt().toFloat()
        }
        val fill = Paint().apply { style = Paint.Style.FILL }
        fill.color = background.argb()
        canvas.drawRect(ox, oy, ox + cw, oy + ch, fill)
        fill.color = foreground.argb()
        for (run in MakeGeometry.runs(matrix))
            canvas.drawRect(ox + run.x * module, oy + run.row * module, ox + (run.x + run.length) * module, oy + (run.row + 1) * module, fill)
        if (text != null) {
            val shown = TextUtils.ellipsize(text, paint, w, TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, x + w / 2, oy + ch + 3 - paint.fontMetrics.ascent, paint)
        }
    }
}

object MakeExportWriter {
    fun data(options: MakeExportOptions, matrix: BitMatrix, foreground: RgbColor, background: RgbColor): ByteArray =
        when (options.format) {
            MakeExportFormat.PNG -> PngRenderer.png(matrix, options.pixelSize, foreground, background)
            MakeExportFormat.SVG -> SvgRenderer.data(matrix, 10, foreground, background)
            MakeExportFormat.PDF ->
                when (options.pdfLayout) {
                    PdfLayout.SINGLE -> PdfRenderer.single(matrix, options.cleanCaption, foreground, background, options.paper)
                    PdfLayout.SHEET -> PdfRenderer.sheet(matrix, options.cleanCaption, options.grid, options.paper, foreground, background)
                }
        }
}
