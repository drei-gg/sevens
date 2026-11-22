package gg.seven.qr

import gg.seven.qr.core.ScannedCode
import gg.seven.qr.core.Symbology
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Save, clipboard retention, Wi-Fi passphrase choice, What's new and codes in view. */
class PlatformFixesTest {
    @Test
    fun saveOffersTheTypeOfEachExport() {
        assertEquals("image/png", FileShare.mimeType("qr-link-20260311-1405.png"))
        assertEquals("image/svg+xml", FileShare.mimeType("qr-link-20260311-1405.svg"))
        assertEquals("application/pdf", FileShare.mimeType("qr-link-20260311-1405-sheet.pdf"))
        assertEquals("application/pdf", FileShare.mimeType(batchFileName(LocalDateTime.of(2026, 11, 22, 14, 5))))
        assertEquals("application/json", FileShare.mimeType(HistoryExportFiles.fileName(json = true)))
        assertEquals("text/csv", FileShare.mimeType(HistoryExportFiles.fileName(json = false)))
        assertEquals("image/png", FileShare.mimeType("QR code.PNG"))
        assertEquals("application/octet-stream", FileShare.mimeType("no-extension"))
    }

    @Test
    fun batchPdfsSuggestADatedName() {
        assertEquals("qr-batch-20261122-1405.pdf", batchFileName(LocalDateTime.of(2026, 11, 22, 14, 5)))
    }

    @Test
    fun theClipboardFolderKeepsOnlyTheLatestImageAndExportsLeaveIt() {
        val cache = Files.createTempDirectory("qr-cache").toFile()
        try {
            val exports = File(cache, "exports")
            val clipboard = File(cache, "clipboard")
            val first = FileShare.replaceIn(clipboard, "a.png", byteArrayOf(1))
            val second = FileShare.replaceIn(clipboard, "b.png", byteArrayOf(2))
            assertFalse(first.exists())
            assertEquals(listOf("b.png"), clipboard.list()!!.toList())
            // A later export sweeps its own folder only: the pasted image is still there.
            FileShare.replaceIn(exports, "qr-history.json", byteArrayOf(3))
            FileShare.replaceIn(exports, "qr-link.svg", byteArrayOf(4))
            assertTrue(second.exists())
            assertEquals(2.toByte(), second.readBytes().single())
            assertEquals(listOf("qr-link.svg"), exports.list()!!.toList())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun wpa3OnlyNetworksGetAWpa3Passphrase() {
        assertEquals(WiFiPassphrase.WPA3, ResultActionPlan.wifiPassphrase("SAE"))
        assertEquals(WiFiPassphrase.WPA3, ResultActionPlan.wifiPassphrase("sae"))
        assertEquals(WiFiPassphrase.WPA3, ResultActionPlan.wifiPassphrase("WPA3"))
        assertEquals(WiFiPassphrase.WPA3, ResultActionPlan.wifiPassphrase("WPA3-SAE"))
        assertEquals(WiFiPassphrase.WPA2, ResultActionPlan.wifiPassphrase("WPA"))
        assertEquals(WiFiPassphrase.WPA2, ResultActionPlan.wifiPassphrase("WPA2"))
        assertEquals(WiFiPassphrase.WPA2, ResultActionPlan.wifiPassphrase("WPA2/WPA3"))
        assertEquals(WiFiPassphrase.WPA2, ResultActionPlan.wifiPassphrase(null))
    }

    @Test
    fun whatsNewReadsTheChangelogAsBlocks() {
        val text =
            """
            # Changelog

            ## 1.1 (not released)

            - Save codes where you pick.
              Wrapped line.
            - Swipe to delete.

            A paragraph
            on two lines.
            """
                .trimIndent()
        assertEquals(
            listOf(
                Changelog.Block.Heading("1.1 (not released)"),
                Changelog.Block.Bullet("Save codes where you pick. Wrapped line."),
                Changelog.Block.Bullet("Swipe to delete."),
                Changelog.Block.Paragraph("A paragraph on two lines."),
            ),
            Changelog.blocks(text),
        )
    }

    @Test
    fun theBundledChangelogHasEntries() {
        val file = File(System.getProperty("user.dir")).resolve("../CHANGELOG.md")
        val blocks = Changelog.blocks(file.readText())
        assertTrue(blocks.first() is Changelog.Block.Heading)
        assertTrue(blocks.any { it is Changelog.Block.Bullet })
    }

    @Test
    fun eachCodeInViewIsNamedByTypeAndPreview() {
        fun code(text: String?, symbology: Symbology = Symbology.QR) =
            ScannedCode(text, (text ?: "x").toByteArray(), symbology)
        assertEquals("QR code: https://example.com", CodeInViewLabel.of(code("https://example.com")))
        assertEquals("Aztec: two words", CodeInViewLabel.of(code("two\n  words", Symbology.AZTEC)))
        assertEquals("QR code: Data that isn’t text", CodeInViewLabel.of(code(null)))
        val long = CodeInViewLabel.of(code("a".repeat(100)))
        assertEquals("QR code: " + "a".repeat(CodeInViewLabel.PREVIEW_LENGTH) + "…", long)
    }
}
