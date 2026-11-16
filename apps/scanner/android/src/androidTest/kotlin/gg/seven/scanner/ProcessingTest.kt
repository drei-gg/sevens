package gg.seven.scanner

import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import gg.seven.scanner.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pixels, OCR and PDFs on the device (iOS ProcessingAudit's checks). */
@RunWith(AndroidJUnit4::class)
class ProcessingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "processing-${UUID.randomUUID()}").apply { mkdirs() }
        PDFBoxResourceLoader.init(context)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun page(text: String) = File(dir, "${UUID.randomUUID()}.jpg").also { Processing.normalize(Demo.page(text), it) }

    @Test
    fun recognizerReadsAPrintedPage() {
        val text = Recognizer.open(context).use { it.text(Processing.rendered(page("SEVENS SCANNER\nReceipt page 1\nCoffee 120\nTotal 120"), pageSettingsDefault())) }
        assertTrue(text, text.contains("SEVENS"))
        assertTrue(text, text.contains("Coffee"))
    }

    @Test
    fun rotationAndResetFollowSettings() {
        val file = page("ROTATE")
        val upright = Processing.rendered(file, pageSettingsDefault())
        val turned = Processing.rendered(file, pageSettingsDefault().copy(quarterTurns = 1, grayscale = true))
        assertEquals(upright.width, turned.height)
        assertEquals(upright.height, turned.width)
        assertEquals(600, upright.width)
    }

    @Test
    fun searchablePdfHasAnInvisibleTextLayerAndImageOnlyHasNone(): Unit = runBlocking {
        val files = listOf(page("LEASE AGREEMENT\nHarbour Road\nTerm twelve months"), page("SIGNATURES\nLandlord\nTenant"))
        val doc = newScanDocument("Lease", files.map { newScanPage("") }, 0.0)
        val searchable = File(dir, "s.pdf")
        Processing.pdf(context, files, doc.pages, searchable, PdfQuality.STANDARD, PaperSize.LETTER, searchable = true)
        PDDocument.load(searchable).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            assertEquals(612f, pdf.getPage(0).mediaBox.width)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text, text.contains("Harbour"))
            assertTrue(text, text.contains("Tenant"))
        }
        val plain = File(dir, "p.pdf")
        Processing.pdf(context, files, doc.pages, plain, PdfQuality.COMPACT, PaperSize.A4, searchable = false)
        PDDocument.load(plain).use { pdf ->
            assertEquals(595f, pdf.getPage(0).mediaBox.width)
            assertTrue(PDFTextStripper().getText(pdf).isBlank())
        }
        assertTrue(plain.length() < searchable.length())
    }

    @Test
    fun pdfImportRasterizesPagesAndRefusesTooMany(): Unit = runBlocking {
        val pdf = File(dir, "two.pdf")
        PdfDocument().apply {
            repeat(2) { i ->
                val p = startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                p.canvas.drawText("Page ${i + 1}", 72f, 72f, android.graphics.Paint().apply { textSize = 24f })
                finishPage(p)
            }
            pdf.outputStream().use { writeTo(it) }
            close()
        }
        val staging = File(dir, "staging").apply { mkdirs() }
        val pages = Processing.importFiles(context, listOf(Uri.fromFile(pdf)), staging)
        assertEquals(2, pages.size)
        val first = Processing.decode(pages[0], 4000)
        assertEquals(2400, first.height)
        assertEquals(1696, first.width)
        // 26 × two pages is over the 50-page limit.
        assertThrows(ScanException.InvalidDocument::class.java) { runBlocking { Processing.importFiles(context, List(26) { Uri.fromFile(pdf) }, staging) } }
        assertThrows(ScanException::class.java) { runBlocking { Processing.importFiles(context, listOf(Uri.fromFile(File(dir, "garbage.pdf").apply { writeText("not a pdf") })), staging) } }
    }

    @Test
    fun encryptedPdfIsRefused(): Unit = runBlocking {
        val source = File(dir, "plain.pdf")
        PdfDocument().apply {
            finishPage(startPage(PdfDocument.PageInfo.Builder(200, 200, 1).create()))
            source.outputStream().use { writeTo(it) }
            close()
        }
        val locked = File(dir, "locked.pdf")
        val encrypted = runCatching {
            PDDocument.load(source).use { doc ->
                doc.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "user", com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()).apply { encryptionKeyLength = 128 })
                doc.save(locked)
            }
        }
        org.junit.Assume.assumeTrue("PDFBox could not encrypt without BouncyCastle", encrypted.isSuccess)
        assertThrows(ScanException.InvalidDocument::class.java) { runBlocking { Processing.importFiles(context, listOf(Uri.fromFile(locked)), File(dir, "s").apply { mkdirs() }) } }
    }
}
