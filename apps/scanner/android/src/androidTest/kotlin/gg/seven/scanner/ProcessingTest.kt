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

    private fun page(text: String) =
        File(dir, "${UUID.randomUUID()}.jpg").also { Processing.normalize(Demo.page(text), it) }

    @Test
    fun cropReachesPixelsOcrAndPdfAndResetKeepsOriginal(): Unit = runBlocking {
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                900,
                1200,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = 68f
            }
        canvas.drawText("RETAIN THIS TEXT", 50f, 180f, paint)
        canvas.drawText("EXCLUDED SECRET", 50f, 1000f, paint)
        val original = File(dir, "crop-original.jpg")
        Processing.normalize(bitmap, original)
        bitmap.recycle()
        val bytes = original.readBytes()
        val settings = pageSettingsDefault().copy(crop = PageCrop(0.0, 0.0, 1.0, 0.5))
        val rendered = Processing.rendered(original, settings)
        assertEquals(900, rendered.width)
        assertEquals(600, rendered.height)
        val recognized = Recognizer.open(context).use { it.text(rendered) }
        rendered.recycle()
        assertTrue(recognized, recognized.contains("RETAIN"))
        assertFalse(recognized, recognized.contains("SECRET"))
        val turned = Processing.rendered(original, settings.copy(quarterTurns = 1))
        assertEquals(600, turned.width)
        assertEquals(900, turned.height)
        turned.recycle()
        val output = File(dir, "cropped.pdf")
        Processing.pdf(
            context,
            listOf(original),
            listOf(newScanPage("").copy(adjustments = settings)),
            output,
            PdfQuality.STANDARD,
            PaperSize.A4,
            searchable = true,
        )
        PDDocument.load(output).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text, text.contains("RETAIN"))
            assertFalse(text, text.contains("SECRET"))
        }
        val reset = Processing.rendered(original, pageSettingsDefault())
        assertEquals(1200, reset.height)
        reset.recycle()
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test
    fun annotationsSurviveReopenBackupCropAndRasterizedPdf(): Unit = runBlocking {
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                900,
                1200,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.WHITE)
            drawText(
                "ORIGINAL TEXT",
                50f,
                900f,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    textSize = 68f
                },
            )
        }
        val source = File(dir, "marked-source.jpg")
        Processing.normalize(bitmap, source)
        bitmap.recycle()
        val originalBytes = source.readBytes()
        val root = File(dir, "store")
        val store = DocumentStore(root)
        val initial = store.create("Marked", listOf(source), listOf("Keep corrected text"))
        val mark = PageAnnotation(PageCrop(0.2, 0.1, 0.8, 0.3), AnnotationStyle.HIGHLIGHT)
        val ink =
            inkFromDisplay(
                listOf(InkPoint(0.2, 0.55), InkPoint(0.8, 0.55)),
                12.0,
                900.0,
                1200.0,
                pageSettingsDefault(),
                InkColor.BLUE,
            )
        store.updateAnnotations(initial.id, initial.pages.single(), listOf(mark), listOf(ink))
        var reopened = DocumentStore(root).list().single()
        assertEquals(4L, reopened.version)
        assertEquals(listOf(mark), reopened.pages.single().annotations)
        assertEquals(listOf(ink), reopened.pages.single().ink)
        assertEquals("Keep corrected text", reopened.pages.single().text)
        assertThrows(ScanException.InvalidDocument::class.java) {
            store.updateAnnotations(initial.id, initial.pages.single(), emptyList())
        }
        val archive = java.io.ByteArrayOutputStream().also { store.exportBackup(it) }.toByteArray()
        val restored = DocumentStore(File(dir, "restored"))
        restored.restoreBackup(java.io.ByteArrayInputStream(archive), archive.size.toLong())
        assertEquals(listOf(mark), restored.list().single().pages.single().annotations)
        assertEquals(listOf(ink), restored.list().single().pages.single().ink)
        assertArrayEquals(originalBytes, store.pageFiles(reopened).single().readBytes())
        fun yellow(pixel: Int) =
            android.graphics.Color.red(pixel) > 220 &&
                android.graphics.Color.green(pixel) > 180 &&
                android.graphics.Color.blue(pixel) < 210
        for (turn in 0L..3L) {
            val settings =
                pageSettingsDefault()
                    .copy(
                        quarterTurns = turn,
                        grayscale = true,
                        crop = PageCrop(0.1, 0.0, 0.9, 1.0),
                    )
            reopened = store.updatePage(initial.id, initial.pages.single().id, settings)
            assertEquals(4L, reopened.version)
            val image =
                Processing.rendered(
                    store.pageFiles(reopened).single(),
                    settings,
                    annotations = reopened.pages.single().annotations,
                    ink = reopened.pages.single().ink,
                )
            val r = annotationDisplayBounds(mark.bounds, settings)
            val x = ((r.x + r.width / 2) * image.width).toInt()
            val y = ((r.y + r.height / 2) * image.height).toInt()
            assertTrue("Mark follows crop/rotation $turn", yellow(image.getPixel(x, y)))
            val geometry =
                inkDisplay(ink, settings, image.width.toDouble(), image.height.toDouble())
            val first = geometry.points.first()
            val last = geometry.points.last()
            val pixel =
                image.getPixel(((first.x + last.x) / 2).toInt(), ((first.y + last.y) / 2).toInt())
            assertTrue(
                "Ink follows crop/rotation $turn",
                android.graphics.Color.blue(pixel) > 150 && android.graphics.Color.red(pixel) < 80,
            )
            image.recycle()
        }
        // Input outside the creation crop must stay hidden after resetting that crop.
        val creation = pageSettingsDefault().copy(crop = PageCrop(0.25, 0.25, 0.75, 0.75))
        val clippedInk =
            inkFromDisplay(
                listOf(InkPoint(-0.4, 0.5), InkPoint(1.4, 0.5)),
                8.0,
                450.0,
                600.0,
                creation,
                InkColor.BLUE,
            )
        val unclippedPage =
            Processing.rendered(
                store.pageFiles(reopened).single(),
                pageSettingsDefault(),
                ink = listOf(clippedInk),
            )
        val outside =
            unclippedPage.getPixel((unclippedPage.width * 0.1).toInt(), unclippedPage.height / 2)
        val inside = unclippedPage.getPixel(unclippedPage.width / 2, unclippedPage.height / 2)
        assertTrue(
            "No clamped edge or off-canvas ink appears after uncrop",
            android.graphics.Color.red(outside) > 230,
        )
        assertTrue(
            "Visible segment remains",
            android.graphics.Color.blue(inside) > 150 && android.graphics.Color.red(inside) < 80,
        )
        unclippedPage.recycle()
        reopened = store.updatePage(initial.id, initial.pages.single().id, pageSettingsDefault())
        for (searchable in listOf(false, true)) {
            val output = File(dir, "marked-$searchable.pdf")
            Processing.pdf(
                context,
                store.pageFiles(reopened),
                reopened.pages,
                output,
                searchable = searchable,
            )
            PDDocument.load(output).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                if (searchable) assertTrue(text, text.contains("ORIGINAL"))
                else assertTrue(text.isBlank())
            }
            android.graphics.pdf
                .PdfRenderer(
                    android.os.ParcelFileDescriptor.open(
                        output,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                )
                .use { renderer ->
                    renderer.openPage(0).use { page ->
                        val rendered =
                            android.graphics.Bitmap.createBitmap(
                                page.width * 2,
                                page.height * 2,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                        rendered.eraseColor(android.graphics.Color.WHITE)
                        page.render(
                            rendered,
                            null,
                            null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                        )
                        val frame = requireNotNull(pdfImageFrame(PaperSize.A4, 900, 1200))
                        val x = ((frame.x + frame.width * 0.5) * 2).toInt()
                        val y =
                            ((page.height - frame.y - frame.height + frame.height * 0.2) * 2)
                                .toInt()
                        assertTrue(
                            "PDF must contain the actual mark",
                            yellow(rendered.getPixel(x, y)),
                        )
                        val inkPixel =
                            rendered.getPixel(
                                ((frame.x + frame.width * 0.5) * 2).toInt(),
                                ((page.height - frame.y - frame.height + frame.height * 0.55) * 2)
                                    .toInt(),
                            )
                        assertTrue(
                            "Actual PDF contains ink",
                            android.graphics.Color.blue(inkPixel) > 150 &&
                                android.graphics.Color.red(inkPixel) < 80,
                        )
                        val out =
                            File(
                                context.getExternalFilesDir(null),
                                "annotation-export-$searchable.png",
                            )
                        out.outputStream().use {
                            rendered.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        rendered.recycle()
                    }
                }
        }
        assertArrayEquals(originalBytes, store.pageFiles(reopened).single().readBytes())
    }

    @Test
    fun recognizerReadsAPrintedPage() {
        val text =
            Recognizer.open(context).use {
                it.text(
                    Processing.rendered(
                        page("SEVENS SCANNER\nReceipt page 1\nCoffee 120\nTotal 120"),
                        pageSettingsDefault(),
                    )
                )
            }
        assertTrue(text, text.contains("SEVENS"))
        assertTrue(text, text.contains("Coffee"))
    }

    @Test
    fun rotationAndResetFollowSettings() {
        val file = page("ROTATE")
        val upright = Processing.rendered(file, pageSettingsDefault())
        val turned =
            Processing.rendered(
                file,
                pageSettingsDefault().copy(quarterTurns = 1, grayscale = true),
            )
        assertEquals(upright.width, turned.height)
        assertEquals(upright.height, turned.width)
        assertEquals(600, upright.width)
    }

    @Test
    fun searchablePdfHasAnInvisibleTextLayerAndImageOnlyHasNone(): Unit = runBlocking {
        val files =
            listOf(
                page("LEASE AGREEMENT\nHarbour Road\nTerm twelve months"),
                page("SIGNATURES\nLandlord\nTenant"),
            )
        val doc = newScanDocument("Lease", files.map { newScanPage("") }, 0.0)
        val searchable = File(dir, "s.pdf")
        Processing.pdf(
            context,
            files,
            doc.pages,
            searchable,
            PdfQuality.STANDARD,
            PaperSize.LETTER,
            searchable = true,
        )
        PDDocument.load(searchable).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            assertEquals(612f, pdf.getPage(0).mediaBox.width)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text, text.contains("Harbour"))
            assertTrue(text, text.contains("Tenant"))
        }
        val plain = File(dir, "p.pdf")
        Processing.pdf(
            context,
            files,
            doc.pages,
            plain,
            PdfQuality.COMPACT,
            PaperSize.A4,
            searchable = false,
        )
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
                p.canvas.drawText(
                    "Page ${i + 1}",
                    72f,
                    72f,
                    android.graphics.Paint().apply { textSize = 24f },
                )
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
        assertThrows(ScanException.InvalidDocument::class.java) {
            runBlocking { Processing.importFiles(context, List(26) { Uri.fromFile(pdf) }, staging) }
        }
        assertThrows(ScanException::class.java) {
            runBlocking {
                Processing.importFiles(
                    context,
                    listOf(Uri.fromFile(File(dir, "garbage.pdf").apply { writeText("not a pdf") })),
                    staging,
                )
            }
        }
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
                doc.protect(
                    com.tom_roush.pdfbox.pdmodel.encryption
                        .StandardProtectionPolicy(
                            "owner",
                            "user",
                            com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission(),
                        )
                        .apply { encryptionKeyLength = 128 }
                )
                doc.save(locked)
            }
        }
        org.junit.Assume.assumeTrue(
            "PDFBox could not encrypt without BouncyCastle",
            encrypted.isSuccess,
        )
        assertThrows(ScanException.InvalidDocument::class.java) {
            runBlocking {
                Processing.importFiles(
                    context,
                    listOf(Uri.fromFile(locked)),
                    File(dir, "s").apply { mkdirs() },
                )
            }
        }
    }
}
