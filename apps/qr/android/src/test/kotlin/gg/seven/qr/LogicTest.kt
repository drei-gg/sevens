package gg.seven.qr

import gg.seven.qr.core.HistoryItem
import gg.seven.qr.core.HistoryKind
import gg.seven.qr.core.Level
import gg.seven.qr.core.LinkInfo
import gg.seven.qr.core.ListsVersion
import gg.seven.qr.core.Payload
import gg.seven.qr.core.PayloadEncoding
import gg.seven.qr.core.ScannedCode
import gg.seven.qr.core.Symbology
import gg.seven.qr.core.TextInfo
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin-side rules, ported from the iOS QRUITests that cover the same functions. */
class ScanDebouncerTest {
    private fun code(text: String) = ScannedCode(text, text.toByteArray(), Symbology.QR)

    @Test
    fun oneCodeIsDeliveredAfterTheDwell() {
        val d = ScanDebouncer()
        assertNull(d.observe(listOf(code("a")), 0))
        assertNull(d.tick(399))
        assertEquals("a", d.tick(400)?.text)
        assertTrue(d.isPaused)
    }

    @Test
    fun severalCodesAreNeverAutoDelivered() {
        val d = ScanDebouncer()
        assertNull(d.observe(listOf(code("a"), code("b")), 0))
        assertTrue(d.hasMultiple)
        assertNull(d.tick(5_000))
        assertEquals("b", d.tap(code("b"))?.text)
    }

    @Test
    fun duplicatesCountOnce() {
        val d = ScanDebouncer()
        d.observe(listOf(code("a"), code("a")), 0)
        assertFalse(d.hasMultiple)
        assertEquals(1, d.codesInView.size)
    }

    @Test
    fun theSameCodeIsSuppressedAfterResumeUntilItLeavesOrTimePasses() {
        val d = ScanDebouncer()
        d.observe(listOf(code("a")), 0)
        assertNotNull(d.tick(400))
        d.resume(1_000)
        assertNull(d.observe(listOf(code("a")), 1_000))
        assertNull(d.tick(2_000))
        assertEquals(4_000L, d.nextWake)
        // After the suppression ends the code needs its dwell again.
        assertNull(d.tick(4_000))
        assertEquals("a", d.tick(4_400)?.text)
    }

    @Test
    fun leavingTheFrameEndsSuppression() {
        val d = ScanDebouncer()
        d.observe(listOf(code("a")), 0)
        d.tick(400)
        d.resume(1_000)
        d.observe(emptyList(), 1_100)
        assertNull(d.observe(listOf(code("a")), 1_200))
        assertEquals("a", d.tick(1_600)?.text)
    }

    @Test
    fun pausedTapsDeliverNothing() {
        val d = ScanDebouncer()
        d.pause()
        assertNull(d.tap(code("a")))
    }
}

class ResultActionPlanTest {
    private val open = ResultAction.OPEN
    private val peek = ResultAction.PEEK

    @Test
    fun aLinkLeadsWithOpenThenPeek() {
        val layout = ResultActionPlan.layout(listOf(peek, open, ResultAction.COPY, ResultAction.SHARE, ResultAction.MAKE_CODE), Level.NORMAL)
        assertEquals(open, layout.primary)
        assertEquals(peek, layout.secondary)
        assertEquals(listOf(ResultAction.COPY, ResultAction.SHARE, ResultAction.MAKE_CODE), layout.chips)
    }

    @Test
    fun aLikelyScamLeadsWithPeekAndMovesOpenToTheChips() {
        val layout = ResultActionPlan.layout(listOf(peek, open, ResultAction.COPY), Level.LIKELY_SCAM)
        assertEquals(peek, layout.primary)
        assertNull(layout.secondary)
        assertEquals(listOf(open, ResultAction.COPY), layout.chips)
    }

    @Test
    fun withoutAnActionOfItsOwnCopyLeads() {
        val layout = ResultActionPlan.layout(listOf(ResultAction.COPY, ResultAction.SHARE, ResultAction.MAKE_CODE), Level.NORMAL)
        assertEquals(ResultAction.COPY, layout.primary)
        assertEquals(listOf(ResultAction.SHARE, ResultAction.MAKE_CODE), layout.chips)
    }

    @Test
    fun onlyHttpLinksWithAHostOpen() {
        fun link(scheme: String, host: String) = LinkInfo("x", scheme, null, host, host, null, null, false, null, "", false, "x")
        assertTrue(ResultActionPlan.canOpen(link("https", "example.org")))
        assertTrue(ResultActionPlan.canOpen(link("HTTP", "example.org")))
        assertFalse(ResultActionPlan.canOpen(link("javascript", "example.org")))
        assertFalse(ResultActionPlan.canOpen(link("https", "")))
    }

    @Test
    fun plainTextOffersCopyShareAndMake() {
        val actions = ResultActionPlan.actions(Payload.Text(TextInfo("hi", false, emptyList())), ResultCapabilities())
        assertEquals(listOf(ResultAction.COPY, ResultAction.SHARE, ResultAction.MAKE_CODE), actions)
        assertEquals(emptyList<ResultAction>(), ResultActionPlan.actions(Payload.Binary(3L), ResultCapabilities()))
    }

    @Test
    fun aScamAsksBeforeOpening() {
        assertTrue(ResultActionPlan.needsOpenConfirmation(Level.LIKELY_SCAM))
        assertFalse(ResultActionPlan.needsOpenConfirmation(Level.CAREFUL))
    }
}

class HistoryTextTest {
    private fun item(payload: String, type: String) =
        HistoryItem(UUID.randomUUID(), HistoryKind.SCANNED, payload, PayloadEncoding.TEXT, Symbology.QR, type, Level.NORMAL, emptyList(), Instant.EPOCH, null)

    @Test
    fun linksShowTheirHost() {
        assertEquals("login.example.com", HistoryRowSummary.host("https://user:pw@Login.Example.com:8443/path?q"))
        assertEquals("[::1]", HistoryRowSummary.host("http://[::1]:80/"))
        assertNull(HistoryRowSummary.host("example.com/path"))
        assertEquals("example.org", HistoryRowSummary.shortPayload(testRes, item("https://example.org/menu", "link")))
    }

    @Test
    fun wifiShowsItsNetworkWithEscapesUndone() {
        assertEquals("Cafe; Guest", HistoryRowSummary.shortPayload(testRes, item("WIFI:T:WPA;S:Cafe\\; Guest;P:x;;", "wifi")))
    }

    @Test
    fun contactsShowTheirName() {
        assertEquals("Ana Reyes", HistoryRowSummary.contactName("BEGIN:VCARD\nVERSION:3.0\nFN:Ana Reyes\nEND:VCARD"))
        assertEquals("Ana Reyes", HistoryRowSummary.contactName("BEGIN:VCARD\nN:Reyes;Ana;;;\nEND:VCARD"))
        assertEquals("Reyes Ana", HistoryRowSummary.contactName("MECARD:N:Reyes,Ana;TEL:1;;"))
    }

    @Test
    fun longAndControlTextIsFlattenedAndCut() {
        assertEquals("a b", HistoryRowSummary.truncated("a\n\u0000b", 60))
        val cut = HistoryRowSummary.truncated("x".repeat(100), 60)
        assertEquals(60, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun emptyStatesFollowTheSavedItems() {
        assertEquals(HistoryEmptyState.READ_ONLY, HistoryEmptyState.resolve(3, true, false, true))
        assertEquals(HistoryEmptyState.LIST, HistoryEmptyState.resolve(3, false, true, false))
        assertEquals(HistoryEmptyState.HISTORY_OFF, HistoryEmptyState.resolve(0, false, true, false))
        assertEquals(HistoryEmptyState.LOCKED, HistoryEmptyState.resolve(0, true, true, false))
        assertEquals(HistoryEmptyState.NO_ITEMS, HistoryEmptyState.resolve(0, true, false, false))
        assertTrue(HistoryEmptyState.showsLockedNote(2, true, true, false))
        assertFalse(HistoryEmptyState.showsLockedNote(2, false, true, false))
    }

    @Test
    fun notesCompareTrimmed() {
        assertFalse(HistoryNote.isChanged("  hi ", "hi"))
        assertTrue(HistoryNote.isChanged("", "hi"))
    }

    @Test
    fun exportNamesUseTheLocalDate() {
        assertEquals("qr-history-2026-11-10.json", HistoryExportFiles.fileName(true, LocalDate.of(2026, 11, 10)))
        assertEquals("qr-history-2026-01-02.csv", HistoryExportFiles.fileName(false, LocalDate.of(2026, 1, 2)))
    }
}

class MakeRulesTest {
    @Test
    fun emailsAndPhonesAreCheckedForShapeOnly() {
        assertTrue(MakeFormValidation.isPlausibleEmail("ana@example.org"))
        assertFalse(MakeFormValidation.isPlausibleEmail("ana@example"))
        assertFalse(MakeFormValidation.isPlausibleEmail("a b@example.org"))
        assertFalse(MakeFormValidation.isPlausibleEmail("ana@.example.org"))
        assertTrue(MakeFormValidation.isPlausiblePhone("+63 (917) 555-0100"))
        assertFalse(MakeFormValidation.isPlausiblePhone("12"))
        assertFalse(MakeFormValidation.isPlausiblePhone("call me"))
        assertEquals(12.5, MakeFormValidation.number("12,5")!!, 0.0)
    }

    @Test
    fun blankRequiredFieldsArePromptsNotErrors() {
        val outcome = MakeFormState().result(MakeFormKind.LINK)
        assertTrue(outcome is MakeOutcome.Invalid && outcome.issue.isBlank)
        val wifi = MakeFormState(ssid = "Home").result(MakeFormKind.WIFI)
        assertTrue(wifi is MakeOutcome.Invalid && wifi.issue.field == MakeFormField.WIFI_PASSWORD)
        val geo = MakeFormState(latitude = "91", longitude = "0").result(MakeFormKind.LOCATION)
        assertTrue(geo is MakeOutcome.Invalid && geo.issue == MakeFormIssue.BadLatitude)
    }

    @Test
    fun anEventCantEndBeforeItStarts() {
        val outcome = MakeFormState(eventTitle = "Party", eventStart = 10_000, eventEnd = 5_000).result(MakeFormKind.EVENT)
        assertTrue(outcome is MakeOutcome.Invalid && outcome.issue == MakeFormIssue.EndBeforeStart)
    }

    @Test
    fun eventsStartAtTheNextFullHour() {
        assertEquals(7_200_000L, MakeFormState.nextHour(Instant.ofEpochMilli(3_600_001)))
        assertEquals(3_600_000L, MakeFormState.nextHour(Instant.ofEpochMilli(3_600_000)))
    }

    @Test
    fun customPngSizesAreClamped() {
        assertEquals(32, MakeExportOptions.clampedSize("4"))
        assertEquals(4096, MakeExportOptions.clampedSize("99999999999999"))
        assertEquals(1024, MakeExportOptions.clampedSize(""))
        assertEquals(1500, MakeExportOptions.clampedSize(" 1500 "))
        assertTrue(MakeExportOptions(preset = null, customSize = "9000", paper = gg.seven.qr.core.PaperSize.A4).customSizeWasClamped)
        assertEquals(8, PngRenderer.scale(125, 1024))
        assertEquals(1, PngRenderer.scale(5000, 1024))
    }

    @Test
    fun exportNamesCarryTheSymbologyKindAndMinute() {
        val at = java.time.LocalDateTime.of(2026, 3, 11, 14, 5)
        assertEquals("qr-link-20260311-1405.png", MakeExportOptions.fileName(MakeSymbology.QR, MakeFormKind.LINK, at, MakeExportFormat.PNG, false))
        assertEquals("aztec-wifi-20260311-1405-sheet.pdf", MakeExportOptions.fileName(MakeSymbology.AZTEC, MakeFormKind.WIFI, at, MakeExportFormat.PDF, true))
    }
}

class ShareAndListsTest {
    @Test
    fun aLoneLinkIsALinkAndAnythingElseIsText() {
        assertEquals(ShareMakeSource.WebUrl("https://maps.app.goo.gl/x"), ShareMakeInput.classify(" https://maps.app.goo.gl/x "))
        assertEquals(ShareMakeSource.Text("Look: https://example.org"), ShareMakeInput.classify("Look: https://example.org"))
        assertNull(ShareMakeInput.decide(ShareMakeSource.OtherFile))
        assertNull(ShareMakeInput.decide(ShareMakeSource.Text("  ")))
    }

    @Test
    fun listsOlderThanSixMonthsSaySo() {
        assertFalse(ListsText.isOld("2026-11-10", LocalDate.of(2027, 5, 10)))
        assertTrue(ListsText.isOld("2026-11-10", LocalDate.of(2027, 5, 11)))
        assertTrue(ListsText.isOld("not a date", LocalDate.of(2026, 1, 1)))
        assertEquals("Scam lists", ListsText.title(testRes, ListsVersion(1, 1, "x")))
    }

    @Test
    fun theListsNeedARepositoryAndAValidKey() {
        // RFC 8032 test vector 1's public key.
        val key = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        assertNull(ListsConfiguration.of("https://github.com/drei-gg/sevens", listOf("", null)))
        assertNull(ListsConfiguration.of("https://example.org/drei-gg/sevens", listOf(key)))
        val config = ListsConfiguration.of("https://github.com/drei-gg/sevens.git", listOf(key, key))
        assertNotNull(config)
        assertEquals(1, config!!.keys.size)
        assertEquals("https://raw.githubusercontent.com/drei-gg/sevens/lists/lists.json", config.fileUrl("lists.json"))
    }

    @Test
    fun peekSendsThreeLanguagesAndNamesTls() {
        assertEquals("en-US,fr;q=0.9,de;q=0.8", PeekClient.acceptLanguage(listOf("en-US", "fr", "de", "es")))
        assertEquals("en", PeekClient.acceptLanguage(emptyList()))
        assertEquals("TLS 1.3", PeekClient.tlsName("TLSv1.3"))
        assertEquals("https://b.example/x", PeekClient.resolve("https://a.example/start", "https://b.example/x"))
        assertEquals("https://a.example/next", PeekClient.resolve("https://a.example/start/", "/next"))
    }

    @Test
    fun signalArgsAreCleanedAndCut() {
        assertEquals("PayPal", SignalText.arg(mapOf("brand" to "Pay​Pal"), "brand"))
        assertNull(SignalText.arg(mapOf("brand" to "\u0000"), "brand"))
        assertTrue(SignalText.arg(mapOf("b" to "y".repeat(50)), "b")!!.endsWith("…"))
    }

}

class CatalogTest {
    /** Every signal is worded from the catalog, with and without its args, and no placeholder leaks. */
    @Test
    fun everySignalHasASentence() {
        val args = mapOf("scheme" to "intent", "host" to "example.com", "brand" to "PayPal", "tld" to ".zip", "kind" to "card", "days" to "3")
        for (id in gg.seven.qr.core.SignalId.entries) {
            for (given in listOf(emptyMap(), args)) {
                val sentence = SignalText.sentence(testRes, gg.seven.qr.core.Signal(id, given))
                assertTrue(id.name, sentence.isNotBlank() && '%' !in sentence)
            }
        }
        assertEquals("This domain was registered 1 day ago.", SignalText.sentence(testRes, gg.seven.qr.core.Signal(gg.seven.qr.core.SignalId.DOMAIN_NEW, mapOf("days" to "1"))))
    }
}

