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
        val actions = ResultActionPlan.actions(analysis.payload, ResultCapabilities())
        assertEquals(listOf(ResultAction.JOIN_WIFI, ResultAction.COPY_PASSWORD, ResultAction.MAKE_CODE), actions)
    }

    @Test
    fun aLookalikeLinkIsALikelyScamWithSentences() {
        val analysis = analyzeOffline("http://paypa1-verify.help/login")
        assertEquals(Level.LIKELY_SCAM, analysis.level)
        val sentences = SignalText.sentences(testRes, analysis.signals)
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
        assertEquals(1, again.search(testRes, "web link").size)
        assertEquals(1, again.search(testRes, "example").size)
    }

    private fun analyzeOffline(text: String) = gg.seven.qr.core.Analyzer(gg.seven.qr.core.bundledDataLoad(null), gg.seven.qr.core.KnownAddresses()).use { it.analyze(scannedCodeFromText(text, Symbology.QR)) }

    @Test
    fun wifiTheSystemSheetCantTakeOffersWifiSettings() {
        val wpa = Payload.Wifi(WiFiInfo("Cafe", "WPA", "hunter22", false, null, null))
        // WEP is one the system sheet can't take: copy the password, then the Wi-Fi panel.
        val wep = ResultActionPlan.actions(Payload.Wifi(WiFiInfo("Old", "WEP", "hunter22", false, null, null)), ResultCapabilities())
        assertEquals(listOf(ResultAction.COPY_PASSWORD, ResultAction.WIFI_SETTINGS, ResultAction.MAKE_CODE), wep)
        val layout = ResultActionPlan.layout(wep, Level.NORMAL)
        assertEquals(ResultAction.COPY_PASSWORD, layout.primary)
        assertEquals(ResultAction.WIFI_SETTINGS, layout.secondary)
        val enterprise = Payload.Wifi(WiFiInfo("Office", "WPA2-EAP", null, false, "PEAP", null))
        assertEquals(WiFiJoinPlan.SETTINGS_ONLY, ResultActionPlan.wifiJoinPlan(enterprise.info))
        assertTrue(ResultAction.WIFI_SETTINGS in ResultActionPlan.actions(enterprise, ResultCapabilities()))
        val noPassword = Payload.Wifi(WiFiInfo("Cafe", "WPA", null, false, null, null))
        assertEquals(listOf(ResultAction.WIFI_SETTINGS), ResultActionPlan.actions(noPassword, ResultCapabilities(), hasText = false))
        val joinable = ResultActionPlan.actions(wpa, ResultCapabilities())
        assertTrue(ResultAction.WIFI_SETTINGS !in joinable)
    }

    private val data = gg.seven.qr.core.bundledDataLoad(null)

    @Test
    fun peekIsOfferedForWebLinksOnly() {
        assertTrue(gg.seven.qr.core.peekAvailable(analyzeOffline("https://example.com/a")))
        assertTrue(gg.seven.qr.core.peekAvailable(analyzeOffline("http://example.com/a")))
        assertTrue(!gg.seven.qr.core.peekAvailable(analyzeOffline("hello there")))
        val text = analyzeOffline("hello there")
        assertTrue(ResultAction.PEEK !in ResultActionPlan.actions(text.payload, ResultCapabilities(), canPeek = false))
        val link = analyzeOffline("https://example.com/a")
        val noPeek = ResultActionPlan.actions(link.payload, ResultCapabilities(), canPeek = false)
        assertTrue(ResultAction.PEEK !in noPeek && ResultAction.OPEN in noPeek)
    }

    @Test
    fun anUnfinishedOrPlainHttpPeekStillLooksUpTheScannedDomain() {
        val original = analyzeOffline("http://shop.example.com/x")
        val notFetched = gg.seven.qr.core.PeekCapture(listOf(gg.seven.qr.core.PeekHop("http://shop.example.com/x", null)), gg.seven.qr.core.StopReason.HTTP_TARGET)
        assertEquals("example.com", gg.seven.qr.core.peekAgeLink(original, notFetched, data)?.registrableDomain)
        assertTrue(PeekText.notFetched(testRes, notFetched)!!.startsWith("Peek doesn’t open pages that aren’t encrypted"))
        val timedOut = gg.seven.qr.core.PeekCapture(listOf(gg.seven.qr.core.PeekHop("https://shop.example.com/x", null)), gg.seven.qr.core.StopReason.TIMEOUT)
        assertEquals("example.com", gg.seven.qr.core.peekAgeLink(analyzeOffline("https://shop.example.com/x"), timedOut, data)?.registrableDomain)
        assertEquals(null, PeekText.notFetched(testRes, timedOut))
        // A page reached on another domain is the one whose age counts.
        val moved = gg.seven.qr.core.PeekCapture(
            listOf(gg.seven.qr.core.PeekHop("https://shop.example.com/x", 301), gg.seven.qr.core.PeekHop("https://other.org/y", 200)),
            gg.seven.qr.core.StopReason.FINISHED,
        )
        assertEquals("other.org", gg.seven.qr.core.peekAgeLink(analyzeOffline("https://shop.example.com/x"), moved, data)?.registrableDomain)
        // The app's analyzer takes the known addresses and still reports.
        val report = gg.seven.qr.core.PeekAnalyzer.withKnown(data, gg.seven.qr.core.KnownAddresses()).use { it.report(original, notFetched, 400) }
        assertEquals(ResultActionPlan.effectiveLevel(original.level, report), maxOf(original.level, report.level))
    }

    @Test
    fun aRaisedPeekVerdictAsksBeforeOpening() {
        val original = analyzeOffline("https://example.com/a")
        val raised = gg.seven.qr.core.PeekReport(null, emptyList(), null, emptyList(), Level.LIKELY_SCAM)
        assertEquals(Level.LIKELY_SCAM, ResultActionPlan.effectiveLevel(original.level, raised))
        assertTrue(ResultActionPlan.needsOpenConfirmation(original.level, raised))
        assertTrue(!ResultActionPlan.needsOpenConfirmation(original.level, null))
        // Peek never lowers an offline verdict.
        val scam = analyzeOffline("http://paypa1-verify.help/login")
        val calm = gg.seven.qr.core.PeekReport(null, emptyList(), null, emptyList(), Level.NORMAL)
        assertEquals(Level.LIKELY_SCAM, ResultActionPlan.effectiveLevel(scam.level, calm))
    }

    @Test
    fun aTextWhoseVerdictComesFromALinkNamesItInTheReasons() {
        val analysis = analyzeOffline("Your parcel is waiting: http://paypa1-verify.help/login")
        assertTrue(analysis.embedded.isNotEmpty())
        val reasons = ResultReasons.rows(testRes, analysis)
        assertTrue(reasons.toString(), reasons.any { it.sentence.startsWith("Link to ") && !it.isInfo })
        assertEquals(reasons.size, reasons.map { it.sentence }.distinct().size)
    }

    @Test
    fun manyLinksInsideShowTheWorstFirstAndCapAtTen() {
        val links = "Our shops: " + (1..12).joinToString(" ") { "https://site$it.example.com/" } + " and http://paypa1-verify.help/login"
        val analysis = analyzeOffline(links)
        assertTrue("${analysis.payload.typeId} ${analysis.embedded.size}", analysis.embedded.size > ResultEmbedded.VISIBLE)
        val shown = ResultEmbedded.visible(analysis.embedded, showAll = false)
        assertEquals(ResultEmbedded.VISIBLE, shown.size)
        assertEquals(analysis.embedded.maxOf { it.level }, shown.first().level)
        assertEquals(analysis.embedded.size, ResultEmbedded.visible(analysis.embedded, showAll = true).size)
    }

    @Test
    fun phoneAndTextMessageCodesNeedACountryCode() {
        val local = MakeFormState(phone = "0917 123 4567").result(MakeFormKind.PHONE)
        assertTrue(local is MakeOutcome.Invalid && local.issue is MakeFormIssue.BadPhone)
        assertTrue(testRes.getString((local as MakeOutcome.Invalid).issue.message).contains("country code"))
        assertTrue(MakeFormState(phone = "+63 917 123 4567").result(MakeFormKind.PHONE) is MakeOutcome.Request)
        assertTrue(MakeFormState(smsNumber = "0063 (917) 123-4567").result(MakeFormKind.SMS) is MakeOutcome.Request)
        assertTrue(MakeFormState(smsNumber = "555-0100").result(MakeFormKind.SMS) is MakeOutcome.Invalid)
        // A contact's phone stays lenient.
        assertTrue(MakeFormState(givenName = "Ana", contactPhone = "0917 123 4567").result(MakeFormKind.CONTACT) is MakeOutcome.Request)
    }

    @Test
    fun batchIssuesAreWordedHere() {
        assertEquals("Enter one code’s text or link per line.", BatchText.issue(testRes, gg.seven.qr.core.batchReview("   \n")))
        val tooMany = gg.seven.qr.core.batchReview((1..500).joinToString("\n") { "line $it" })
        assertTrue(tooMany.issueKind is gg.seven.qr.core.BatchIssue.TooManyLines)
        assertTrue(BatchText.issue(testRes, tooMany)!!.startsWith("Use at most "))
        assertEquals(null, BatchText.issue(testRes, gg.seven.qr.core.batchReview("one\ntwo")))
    }

    @Test
    fun theCsvExportReadsKnownAddresses() {
        val csv = gg.seven.qr.core.historyExportCsvWithKnown(emptyList(), gg.seven.qr.core.KnownAddresses())
        assertTrue(csv.isNotEmpty())
    }
}
