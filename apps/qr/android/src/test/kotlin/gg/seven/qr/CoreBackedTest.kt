package gg.seven.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import gg.seven.qr.core.BitMatrix
import gg.seven.qr.core.Level
import gg.seven.qr.core.MakeRequest
import gg.seven.qr.core.Payload
import gg.seven.qr.core.WiFiInfo
import gg.seven.qr.core.payloadEncode
import gg.seven.qr.core.scannedCodeFromText
import gg.seven.qr.core.Symbology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Make's generator and the result plan against the real core on the JVM. */
class CoreBackedTest {
    /** The matrix scaled up as a luminance image, read back with ZXing's own decoder. */
    private fun read(matrix: BitMatrix, symbology: MakeSymbology): String? {
        val scale = 4
        val rows = MakeGeometry.rows(matrix)
        val w = matrix.width.toInt() * scale
        val h = rows * scale
        val pixels = ByteArray(w * h) { i -> if (MakeGeometry.dark(matrix, (i % w) / scale, (i / w) / scale)) 0 else -1 }
        val source = object : LuminanceSource(w, h) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = pixels.copyOfRange(y * w, (y + 1) * w)
            override fun getMatrix(): ByteArray = pixels
        }
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        return runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
    }

    @Test
    fun everySymbologyReadsBack() {
        for (s in MakeSymbology.entries) {
            val text = if (s == MakeSymbology.CODE128) "QR-KIT-128" else "https://example.org/menu?x=1"
            assertEquals(s.name, text, read(CodeGenerator.generate(text, s, Correction.AUTO), s))
        }
    }

    @Test
    fun utf8TextReadsBackWithoutAnEci() {
        val text = "Café ☕ menu"
        assertEquals(text, read(CodeGenerator.generate(text, MakeSymbology.QR, Correction.M), MakeSymbology.QR))
    }

    @Test
    fun autoCorrectionNeverGrowsTheCode() {
        val text = "https://example.org/a/fairly/long/path?with=query&and=more"
        val auto = CodeGenerator.generate(text, MakeSymbology.QR, Correction.AUTO)
        val m = CodeGenerator.generate(text, MakeSymbology.QR, Correction.M)
        assertEquals(CodeGenerator.qrVersion(m), CodeGenerator.qrVersion(auto))
        assertNotNull(CodeGenerator.autoLevel(text))
    }

    @Test
    fun capitalisedLinksMakeSmallerCodes() {
        val typed = "https://example.org/" + "A1B2C3D4".repeat(6)
        val compact = gg.seven.qr.core.linkCompacted(typed)!!
        val before = CodeGenerator.qrVersion(CodeGenerator.generate(typed, MakeSymbology.QR, Correction.AUTO))!!
        val after = CodeGenerator.qrVersion(CodeGenerator.generate(compact, MakeSymbology.QR, Correction.AUTO))!!
        assertTrue("$after < $before", after <= before)
    }

    @Test
    fun tooLongAndNonAsciiAreRefused() {
        val tooLong = runCatching { CodeGenerator.generate("x".repeat(3_000), MakeSymbology.QR, Correction.H) }.exceptionOrNull()
        assertEquals(MakeError.TOO_LONG, (tooLong as MakeException).error)
        val ascii = runCatching { CodeGenerator.generate("café", MakeSymbology.CODE128, Correction.AUTO) }.exceptionOrNull()
        assertEquals(MakeError.NOT_ASCII, (ascii as MakeException).error)
    }

    @Test
    fun aWifiCodeMadeHereIsReadAsWifi() {
        val text = payloadEncode(MakeRequest.Wifi(WiFiInfo("Cafe; Guest", "WPA", "hunter22", false, null, null)))
        val analysis = analyzeOffline(text)
        assertTrue(analysis.payload is Payload.Wifi)
        assertEquals("Cafe; Guest", (analysis.payload as Payload.Wifi).info.ssid)
        val actions = ResultActionPlan.actions(analysis.payload, ResultCapabilities(wifiJoinAvailable = true))
        assertEquals(listOf(ResultAction.JOIN_WIFI, ResultAction.COPY_PASSWORD, ResultAction.MAKE_CODE), actions)
    }

    @Test
    fun aLookalikeLinkIsALikelyScamWithSentences() {
        val analysis = analyzeOffline("http://paypa1-verify.help/login")
        assertEquals(Level.LIKELY_SCAM, analysis.level)
        val sentences = SignalText.sentences(analysis.signals)
        assertTrue(sentences.isNotEmpty())
        assertTrue(sentences.none { it.contains("safe", ignoreCase = true) })
        assertEquals(ResultAction.PEEK, ResultActionPlan.layout(ResultActionPlan.actions(analysis.payload, ResultCapabilities()), analysis.level).primary)
    }

    @Test
    fun theHistoryFileRoundTripsThroughTheStore() = kotlinx.coroutines.runBlocking {
        val dir = kotlin.io.path.createTempDirectory("qr-history").toFile()
        val store = HistoryStore(dir, kotlinx.coroutines.GlobalScope, debounceMillis = 0)
        store.load()
        val item = gg.seven.qr.core.historyItemFromAnalysis(analyzeOffline("https://example.org"), gg.seven.qr.core.HistoryKind.SCANNED, java.time.Instant.now())
        assertTrue(store.add(item))
        store.updateNote("  menu ", item.id)
        store.flush()
        val again = HistoryStore(dir, kotlinx.coroutines.GlobalScope)
        again.load()
        assertEquals("menu", again.state.value.items.single().note)
        assertEquals(1, again.search("web link").size)
        assertEquals(1, again.search("example").size)
    }

    private fun analyzeOffline(text: String) = gg.seven.qr.core.Analyzer(gg.seven.qr.core.bundledDataLoad(null), gg.seven.qr.core.KnownAddresses()).use { it.analyze(scannedCodeFromText(text, Symbology.QR)) }

    @Test
    fun wifiTheSystemSheetCantTakeOffersWifiSettings() {
        val wpa = Payload.Wifi(WiFiInfo("Cafe", "WPA", "hunter22", false, null, null))
        val older = ResultActionPlan.actions(wpa, ResultCapabilities(wifiJoinAvailable = false))
        assertEquals(listOf(ResultAction.COPY_PASSWORD, ResultAction.WIFI_SETTINGS, ResultAction.MAKE_CODE), older)
        val layout = ResultActionPlan.layout(older, Level.NORMAL)
        assertEquals(ResultAction.COPY_PASSWORD, layout.primary)
        assertEquals(ResultAction.WIFI_SETTINGS, layout.secondary)
        val enterprise = Payload.Wifi(WiFiInfo("Office", "WPA2-EAP", null, false, "PEAP", null))
        assertEquals(WiFiJoinPlan.SETTINGS_ONLY, ResultActionPlan.wifiJoinPlan(enterprise.info, ResultCapabilities(wifiJoinAvailable = true)))
        assertTrue(ResultAction.WIFI_SETTINGS in ResultActionPlan.actions(enterprise, ResultCapabilities(wifiJoinAvailable = true)))
        val noPassword = Payload.Wifi(WiFiInfo("Cafe", "WPA", null, false, null, null))
        assertEquals(listOf(ResultAction.WIFI_SETTINGS), ResultActionPlan.actions(noPassword, ResultCapabilities(wifiJoinAvailable = true), hasText = false))
        val joinable = ResultActionPlan.actions(wpa, ResultCapabilities(wifiJoinAvailable = true))
        assertTrue(ResultAction.WIFI_SETTINGS !in joinable)
    }
}
