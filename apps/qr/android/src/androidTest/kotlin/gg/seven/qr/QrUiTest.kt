package gg.seven.qr

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
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

/**
 * The main flows on a device: a scan's verdict, History, Make. The generic build is unlocked, so
 * the trial and the gate are tested on the personal flavor (src/androidTestPersonal).
 */
@RunWith(AndroidJUnit4::class)
class QrUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun batchReviewSurvivesRotationAndExportsEveryCodeAcrossPages() {
        if (
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("featureOrientation") == "landscape"
        ) {
            ui.runOnUiThread {
                ui.activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            ui.waitUntil(5_000) {
                ui.activity.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
        }

        model.store.startTrial()
        ui.onNodeWithTag("scan.make").performClick()
        ui.onNodeWithText("Batch").performClick()
        val items = (1..7).map { "https://example.org/item/$it?utm_source=labels" }
        ui.onNodeWithTag("batch-input").performTextInput(items.joinToString("\n"))
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("batch-input").assertTextContains(items.joinToString("\n"))
        ui.onNodeWithTag("editor-save").performClick()
        ui.onNodeWithText("7 codes").assertExists()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        capture("batch-review")
        ui.onNodeWithText("Code 7").performScrollTo()
        ui.onNodeWithTag("editor-save").performClick()
        val draft = (model.nav.stack.value.last() as Route.Make).draft.batch
        ui.waitUntil(15_000) { draft.prepared != null || draft.issue != null }
        assertEquals(null, draft.issue)
        ui.onNodeWithText("Code 7").performScrollTo()
        ui.onNodeWithTag("editor-save")
            .assertTextContains("Share PDF")
            .assertIsDisplayed()
            .assertIsEnabled()
        capture("batch-ready")
        ui.onNodeWithTag("editor-save").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitUntil(5_000) {
            automation.rootInActiveWindow?.packageName?.toString() in
                listOf("android", "com.android.intentresolver")
        }
        automation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        )
        ui.waitForIdle()
        ui.onNodeWithTag("editor-save").assertIsDisplayed()
        assertEquals(items, draft.items)

        val file = requireNotNull(draft.prepared)
        try {
            val descriptor =
                android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
            val decoded = mutableListOf<String>()
            android.graphics.pdf.PdfRenderer(descriptor).use { pdf ->
                assertEquals(2, pdf.pageCount)
                repeat(pdf.pageCount) { index ->
                    pdf.openPage(index).use { page ->
                        val bitmap =
                            android.graphics.Bitmap.createBitmap(
                                page.width * 2,
                                page.height * 2,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                        )
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val source =
                            com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                        val binary =
                            com.google.zxing.BinaryBitmap(
                                com.google.zxing.common.HybridBinarizer(source)
                            )
                        decoded +=
                            com.google.zxing.multi.qrcode
                                .QRCodeMultiReader()
                                .decodeMultiple(binary)
                                .map { it.text }
                        bitmap.recycle()
                    }
                }
            }
            assertEquals(items.sorted(), decoded.sorted())
            ui.onNodeWithText("Edit inputs").performScrollTo().performClick()
            assertEquals(null, draft.prepared)
            ui.onNodeWithTag("batch-input").assertTextContains(items.joinToString("\n"))
        } finally {
            file.delete()
        }
    }

    private val model
        get() = ViewModelProvider(ui.activity)[QrModel::class.java]

    @Before
    fun prepare() {
        // The camera prompt would pause the activity; the permission flow is checked by hand.
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(ui.activity.packageName, android.Manifest.permission.CAMERA)
        model.store.beforeYouBuyShown = true
        ui.activity.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        runBlocking { model.clearHistory() }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10_000) {
            runCatching { ui.onAllNodesWithTag("scan.make").fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
        ui.waitForIdle()
        if (ui.onAllNodesWithText("Just scan for free").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithText("Just scan for free").performClick()
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io
            .File(instrumentation.targetContext.getExternalFilesDir(null), "deep-$name.png")
            .outputStream()
            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun m3ReviewContentType() {
        model.store.startTrial()
        ui.onNodeWithTag("scan.make").performClick()
        ui.onNodeWithTag("make.type").assertExists()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        capture("m3-qr-content-type")
        MakeFormKind.entries.forEach { kind ->
            ui.onNodeWithTag("make.type").performScrollTo().performClick()
            if (kind == MakeFormKind.entries.first()) capture("m3-qr-content-menu")
            ui.onNode(hasText(ui.activity.getString(kind.title)) and hasAnyAncestor(isPopup()))
                .performScrollTo()
                .performClick()
            assertEquals(kind, (model.nav.stack.value.last() as Route.Make).draft.kind.value)
        }
    }

    @Test
    fun resultSplitButtonExposesBrowserAlternativeWithoutOpeningIt() {
        model.pendingCode.value = textCode("https://example.org/menu")
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("result.verdict").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithContentDescription("More ways to open").performClick()
        ui.onNodeWithText("Open in browser").assertIsDisplayed()
        capture("qr-open-split-button")
    }

    @Test
    fun makeWifiUsesMaskedPasswordAndNativeSecurityPicker() {
        model.store.startTrial()
        ui.onNodeWithTag("scan.make").performClick()
        ui.onNodeWithTag("make.type").performScrollTo().performClick()
        ui.onNodeWithText("Wi-Fi network").performScrollTo().performClick()
        ui.onNodeWithTag("make.field.ssid").performTextInput("Studio")
        ui.onNodeWithTag("make.field.wifiPassword")
            .performScrollTo()
            .performTextInput("sample-password")
        ui.onNodeWithContentDescription("Show password").performClick()
        ui.onNodeWithTag("make.field.wifiPassword").assertTextContains("sample-password")
        ui.onNodeWithContentDescription("Hide password").performClick()
        ui.onNodeWithTag("make.field.wifiPassword")
            .assert(
                SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.Password
                )
            )
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("make.field.ssid").assertTextContains("Studio")
        ui.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        ui.onNodeWithTag("make.field.wifiPassword").assertTextContains("sample-password")
        ui.onNodeWithContentDescription("Hide password").performClick()
        ui.onNodeWithTag("make.field.security").performClick()
        capture("qr-wifi-security")
    }

    @Test
    fun visibleHistorySelectionDeletesAndUndoRestores() {
        model.store.startTrial()
        model.pendingCode.value = textCode("https://example.org/selection")
        ui.waitUntil(10_000) { model.historyState.value.items.isNotEmpty() }
        ui.onNodeWithTag("result.done").performClick()
        ui.onNodeWithTag("scan.history").performClick()
        ui.onNodeWithContentDescription("Select history").performClick()
        ui.onNodeWithContentDescription("Delete selected").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Select all shown").performClick()
        capture("qr-history-selection")
        ui.onNodeWithContentDescription("Delete selected").performClick()
        ui.waitUntil(5_000) { model.historyState.value.items.isEmpty() }
        ui.onNodeWithText("Undo").performClick()
        ui.waitUntil(5_000) { model.historyState.value.items.size == 1 }
        ui.onNodeWithTag("history.row").assertHasClickAction().performClick()
        ui.onNodeWithText("Contents").assertExists()
        capture("qr-history-detail")
    }

    @Test
    fun aScamLinkShowsItsVerdictAndReasonsWithPeekLeading() {
        model.pendingCode.value = textCode("http://paypa1-verify.help/login")
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("result.verdict").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("result.verdict")
            .assertContentDescriptionContains("Likely scam", substring = true)
        ui.onNodeWithTag("result.action.peek").assertExists()
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
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("make.code", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        ui.onNodeWithTag("make.code", useUnmergedTree = true)
            .assertContentDescriptionContains("QR code for https://example.org")
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
        store.add(
            gg.seven.qr.core.historyItemFromAnalysis(
                analysis,
                HistoryKind.SCANNED,
                java.time.Instant.now(),
            )
        )
        store.updateNote("  Lunch  ", store.state.value.items.single().id)
        store.flush()
        val again = HistoryStore(dir, kotlinx.coroutines.GlobalScope)
        again.load()
        assertEquals("Lunch", again.state.value.items.single().note)
        assertEquals(1, again.search(context.resources, "wi-fi").size)
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
            val text =
                if (symbology == MakeSymbology.CODE128) "QR-KIT-128"
                else "https://example.org/menu?x=1"
            val matrix = CodeGenerator.generate(text, symbology, Correction.AUTO)
            val bitmap = PngRenderer.bitmap(matrix, 1024, BLACK, WHITE)!!
            val read = ImageScanner.scan(bitmap)
            assertEquals(symbology.name, text, read.firstOrNull()?.text)
        }
    }
}
