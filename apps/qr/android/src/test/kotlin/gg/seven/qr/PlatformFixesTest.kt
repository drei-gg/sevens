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
        assertEquals("QR code: https://example.com", CodeInViewLabel.of(testRes, code("https://example.com")))
        assertEquals("Aztec: two words", CodeInViewLabel.of(testRes, code("two\n  words", Symbology.AZTEC)))
        assertEquals("QR code: Data that isn’t text", CodeInViewLabel.of(testRes, code(null)))
        val long = CodeInViewLabel.of(testRes, code("a".repeat(100)))
        assertEquals("QR code: " + "a".repeat(CodeInViewLabel.PREVIEW_LENGTH) + "…", long)
    }

    @Test
    fun paymentRowsHaveHumanLabelsAndNoRawKeys() {
        val info =
            gg.seven.qr.core.PaymentInfo(
                format = gg.seven.qr.core.PaymentFormat.EMV,
                payeeName = "Sari Store",
                payeeAccount = "09171234567",
                city = "Manila",
                country = "PH",
                amount = "250.00",
                currency = "PHP",
                network = "QR Ph",
                isDynamic = false,
                reference = "INV-1",
                party = gg.seven.qr.core.PaymentParty.UNKNOWN,
                checksum = gg.seven.qr.core.PaymentChecksum.VALID,
                extra = mapOf("mcc" to "5411", "networkID" to "ph.ppmi", "additional.05" to "R-9", "additional.42" to "x", "someNewKey" to "y", "alternateName" to "Tindahan"),
            )
        val rows = PaymentText.rows(testRes, info, java.util.Locale.ENGLISH)
        assertEquals(listOf("City", "Country", "Network", "Account", "Reference"), rows.map { it.label })
        assertEquals("Philippines (PH)", rows[1].value)
        assertEquals("PHP 250.00", PaymentText.amount(testRes, info))
        val more = PaymentText.moreDetails(testRes, info).map { it.label }
        assertEquals("Format", more.first())
        assertTrue(more.containsAll(listOf("Merchant category code", "Network identifier", "Reference", "Additional data 42", "Some new key")))
        assertTrue(more.none { it == "mcc" || it == "networkID" || it.contains('.') || it == "Name in another language" })
        assertEquals("IBAN", PaymentText.accountLabel(testRes, gg.seven.qr.core.PaymentFormat.EPC))
        assertEquals("Payee address", PaymentText.accountLabel(testRes, gg.seven.qr.core.PaymentFormat.UPI))
        assertEquals("Check this matches where you are.", testRes.getString(R.string.qr_payment_check_match))
        assertEquals("Mandate (repeating payments)", PaymentText.value(testRes, "kind", "MANDATE"))
        assertEquals("XK1", PaymentText.country(testRes, "XK1"))
    }
}
