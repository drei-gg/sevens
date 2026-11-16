package gg.seven.qr

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.qr.core.HistoryKind
import gg.seven.qr.core.Level
import gg.seven.qr.core.Payload
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main flows on a device: a scan's verdict, History saving behind the trial, Make. */
@RunWith(AndroidJUnit4::class)
class QrUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private val model
        get() = ViewModelProvider(ui.activity)[QrModel::class.java]

    @Before
    fun prepare() {
        // The camera prompt would pause the activity; the permission flow is checked by hand.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ui.activity.packageName, android.Manifest.permission.CAMERA)
        model.store.beforeYouBuyShown = true
        ui.activity.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        runBlocking { model.clearHistory() }
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        if (ui.onAllNodesWithText("Just scan for free").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithText("Just scan for free").performClick()
    }

    @Test
    fun aScamLinkShowsItsVerdictAndReasonsWithPeekLeading() {
        model.pendingCode.value = textCode("http://paypa1-verify.help/login")
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("result.verdict").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("result.verdict").assertContentDescriptionContains("Likely scam", substring = true)
        ui.onNodeWithTag("result.action.peek").assertExists()
        // Without the trial, nothing new is saved.
        assertTrue(model.historyState.value.items.isEmpty())
    }

    @Test
    fun duringTheTrialScansAreSavedAndListed() {
        model.store.startTrial()
        model.pendingCode.value = textCode("https://example.org/menu")
        ui.waitUntil(10_000) { model.historyState.value.items.isNotEmpty() }
        ui.onNodeWithTag("result.done").performClick()
        ui.onNodeWithTag("scan.history").performClick()
        ui.onNode(hasContentDescription("example.org", substring = true)).assertExists()
    }

    @Test
    fun makeDrawsACodeAndOffersExport() {
        model.store.startTrial()
        ui.onNodeWithTag("scan.make").performClick()
        ui.onNodeWithTag("make.field.link").performTextInput("https://example.org")
        // Export hides while the keyboard is up; the code itself shows as soon as it is made.
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("make.code", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("make.code", useUnmergedTree = true).assertContentDescriptionContains("QR code for https://example.org")
    }
}

/** The stores and the core on the device's own runtime. */
@RunWith(AndroidJUnit4::class)
class QrStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun historySurvivesAReloadAndReadsBackTheSameItems() = runBlocking {
        QrLists.start(context)
        val dir = File(context.cacheDir, "history-test").apply { deleteRecursively() }
        val store = HistoryStore(dir, kotlinx.coroutines.GlobalScope, debounceMillis = 0)
        store.load()
        val analysis = analyze(textCode("WIFI:T:nopass;S:Cafe;;"))
        assertTrue(analysis.payload is Payload.Wifi)
        assertEquals(Level.CAREFUL, analysis.level)
        store.add(gg.seven.qr.core.historyItemFromAnalysis(analysis, HistoryKind.SCANNED, java.time.Instant.now()))
        store.updateNote("  Lunch  ", store.state.value.items.single().id)
        store.flush()
        val again = HistoryStore(dir, kotlinx.coroutines.GlobalScope)
        again.load()
        assertEquals("Lunch", again.state.value.items.single().note)
        assertEquals(1, again.search("wi-fi").size)
    }

    @Test
    fun aKnownAddressDropsTheListBasedWarning() = runBlocking {
        QrLists.start(context)
        val dir = File(context.cacheDir, "known-test").apply { deleteRecursively() }
        val known = KnownAddressStore(dir)
        known.load()
        val code = textCode("https://paypal.pt")
        val before = analyze(code, known.addresses.value)
        val domain = gg.seven.qr.core.knownAddressesOffer(known.addresses.value, before)!!
        assertTrue(known.add(domain))
        val after = analyze(code, known.addresses.value)
        assertTrue(after.signals.any { it.id == gg.seven.qr.core.SignalId.KNOWN_BY_YOU })
        assertTrue(after.level < before.level || before.level == Level.NORMAL)
    }

    @Test
    fun makeRoundTripsThroughTheReader() {
        QrLists.start(context)
        for (symbology in MakeSymbology.entries) {
            val text = if (symbology == MakeSymbology.CODE128) "QR-KIT-128" else "https://example.org/menu?x=1"
            val matrix = CodeGenerator.generate(text, symbology, Correction.AUTO)
            val bitmap = PngRenderer.bitmap(matrix, 1024, BLACK, WHITE)!!
            val read = ImageScanner.scan(bitmap)
            assertEquals(symbology.name, text, read.firstOrNull()?.text)
        }
    }
}
