package gg.seven.scanner

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.scanner.core.ScanDocument
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main flows, driven through the UI on the demo library (iOS ScannerUITests). */
@RunWith(AndroidJUnit4::class)
class ScannerUiTest {
    // Queue composition on the test dispatcher; the old unconfined rule can resume on IO threads.
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private lateinit var model: ScannerModel

    @Before
    fun prepare() {
        ui.waitForIdle()
        if (ui.onAllNodesWithTag("seven-look-around").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("seven-look-around").performClick()
        model = ViewModelProvider(ui.activity)[ScannerModel::class.java]
        ui.waitUntil(10_000) { model.loaded.value }
        val app = ui.activity.application
        app.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        ui.runOnUiThread {
            model.store.access.startTrial()
            model.store.refresh()
        }
        ui.waitUntil(5_000) { model.store.paidFeaturesAvailable }
        File(app.filesDir, ScannerModel.LIBRARY).deleteRecursively()
        Demo.demo(app, model.repository)
        runBlocking { model.reload() }
        ui.waitForIdle()
    }

    /**
     * Whether access can lapse in this build: the generic flavor is built from source with
     * everything unlocked, and a personal build may carry its explicit unlock. Trial-expiry checks
     * run on personal builds without the unlock (and store builds).
     */
    private val trialApplies: Boolean
        get() = !BuildConfig.PERSONAL_UNLOCK && !model.store.purchase.value.sourceBuild

    private fun stored(title: String): ScanDocument =
        model.repository.list().first { it.title == title }

    private fun card(title: String): SemanticsNodeInteraction {
        val matcher =
            hasTestTag("scanner.document") and hasContentDescription(title, substring = true)
        ui.onNodeWithTag("scanner.library").performScrollToNode(matcher)
        return ui.onNode(matcher)
    }

    private fun open(title: String) {
        card(title).performClick()
        ui.waitForIdle()
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
    fun annotationsDrawUndoRecreateRetryAndReopen() {
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
        open("Café receipt")
        val original = stored("Café receipt")
        val file = model.repository.pageFiles(original).single()
        val before = file.readBytes()
        ui.onNodeWithTag("scanner.page.1").performScrollTo().performClick()
        ui.onNodeWithTag("page.annotate").performScrollTo().performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("annotations-canvas").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("annotations-canvas").performScrollTo().performTouchInput {
            swipe(
                androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.3f),
                androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.6f),
            )
        }
        ui.onNodeWithTag("annotations-count").assertTextEquals("1 of 100 marks")
        ui.onNodeWithTag("annotations-undo").performScrollTo().performClick()
        ui.onNodeWithTag("annotations-count").assertTextEquals("0 of 100 marks")
        ui.onNodeWithTag("annotations-exact").performScrollTo().performClick()
        ui.onNodeWithTag("page.cropRight").performScrollTo().performTextReplacement("0")
        ui.onNodeWithText("Apply").assertIsNotEnabled()
        ui.onNodeWithTag("page.cropRight").performTextReplacement("90")
        ui.onNodeWithText("Apply").performClick()
        ui.onNodeWithTag("annotations-count").assertTextEquals("1 of 100 marks")
        ui.onNodeWithTag("annotations-undo").performScrollTo().performClick()
        ui.onNodeWithTag("annotations-redo").performClick()
        ui.onNodeWithText("Blue box").performScrollTo().performClick()
        ui.onNodeWithTag("annotations-exact").performScrollTo().performClick()
        ui.onNodeWithText("Apply").performClick()
        ui.onNodeWithText("Ink", useUnmergedTree = true).performScrollTo().performClick()
        ui.onNodeWithText("Blue", useUnmergedTree = true).performScrollTo().performClick()
        ui.onNodeWithText("Bold", useUnmergedTree = true).performScrollTo().performClick()
        ui.onNodeWithTag("ink-canvas").performScrollTo().performTouchInput {
            swipe(
                androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f),
                androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.7f),
                700,
            )
        }
        ui.waitUntil(10_000) { model.annotationDraft.value?.ink?.size == 1 }
        // A cancelled input must not publish partial ink or leave Save stuck disabled.
        ui.onNodeWithTag("ink-canvas").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * 0.3f, height * 0.4f))
            moveTo(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.5f))
            cancel()
        }
        ui.waitUntil(5_000) { model.annotationDraft.value?.inking == false }
        assertEquals(1, model.annotationDraft.value?.ink?.size)
        ui.onNodeWithTag("annotations-undo").performScrollTo().performClick()
        ui.onNodeWithTag("ink-count").assertTextEquals("0 of 100 ink strokes")
        ui.onNodeWithTag("annotations-redo").performClick()
        ui.onNodeWithTag("ink-count").assertTextEquals("1 of 100 ink strokes")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("ink-count").assertTextEquals("1 of 100 ink strokes")
        ui.onNodeWithTag("annotations-count").assertTextEquals("2 of 100 marks")
        ui.onNodeWithTag("annotations-canvas").performScrollTo()
        Thread.sleep(500)
        val canvasBounds = ui.onNodeWithTag("annotations-canvas").getUnclippedBoundsInRoot()
        val saveBounds = ui.onNodeWithTag("editor-save").getUnclippedBoundsInRoot()
        assertTrue(
            "The complete canvas must clear the fixed toolbar",
            canvasBounds.top >= saveBounds.bottom,
        )
        val heightDp = ui.activity.resources.configuration.screenHeightDp
        assertTrue("The complete canvas must fit the window", canvasBounds.bottom.value <= heightDp)
        capture("scanner-annotations")
        ui.runOnUiThread {
            val focused =
                android.view.inspector.WindowInspector.getGlobalWindowViews().first {
                    it.hasWindowFocus()
                }
            com.google.android.apps.common.testing.accessibility.framework.integrations.espresso
                .AccessibilityValidator()
                .setThrowExceptionFor(
                    com.google.android.apps.common.testing.accessibility.framework
                        .AccessibilityCheckResult
                        .AccessibilityCheckResultType
                        .ERROR
                )
                .check(focused)
        }
        // Simulate an unavailable original: failure must keep this draft and must not publish
        // marks.
        val held = File(file.parentFile, "qa-held-original")
        assertTrue(file.renameTo(held))
        try {
            ui.onNodeWithTag("editor-save").performClick()
            ui.waitUntil(10_000) { model.annotationDraft.value?.failure != null }
            assertEquals(2, model.annotationDraft.value?.marks?.size)
            assertEquals(1, model.annotationDraft.value?.ink?.size)
            assertTrue(model.annotationDraft.value?.working == false)
        } finally {
            assertTrue(held.renameTo(file))
        }
        ui.onNodeWithText("Keep editing").performClick()
        assertTrue(stored("Café receipt").pages.single().annotations.isEmpty())
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(10_000) { model.annotationDraft.value == null }
        val saved = stored("Café receipt")
        assertEquals(4L, saved.version)
        assertEquals(1, saved.pages.single().ink.size)
        assertEquals(2, saved.pages.single().annotations.size)
        assertEquals(original.pages.single().text, saved.pages.single().text)
        assertArrayEquals(before, file.readBytes())
        ui.onNodeWithTag("page.annotate").performScrollTo().performClick()
        ui.waitUntil(5_000) { model.annotationDraft.value != null }
        ui.onNodeWithTag("annotations-count").assertTextEquals("2 of 100 marks")
        ui.onNodeWithTag("annotations-clear").performScrollTo().performClick()
        ui.onNodeWithTag("annotations-count").assertTextEquals("0 of 100 marks")
        ui.onNodeWithTag("editor-cancel").performClick()
        ui.onNodeWithText("Discard").performClick()
        assertEquals(2, stored("Café receipt").pages.single().annotations.size)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun cropBoundsSurviveRecreationAndSaveWithoutReplacingOriginal() {
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
        open("Café receipt")
        val original = stored("Café receipt")
        val file = model.repository.pageFiles(original).single()
        val bytes = file.readBytes()
        ui.onNodeWithTag("scanner.page.1").performScrollTo().performClick()
        ui.onNodeWithTag("page.crop").performScrollTo().performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("page.cropCanvas").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("page.cropCanvas").performScrollTo().performTouchInput {
            swipe(
                androidx.compose.ui.geometry.Offset(width / 2f, height - 4f),
                androidx.compose.ui.geometry.Offset(width / 2f, height * 0.65f),
            )
        }
        capture("scanner-crop-handles")
        ui.onNodeWithTag("page.cropBounds").performScrollTo().performClick()
        val bottomBeforeTyping =
            ui.onNodeWithTag("page.cropBottom")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
                .text
                .toDouble()
        assertTrue("Dragging the native handle must change the crop", bottomBeforeTyping < 95.0)
        ui.onNodeWithTag("page.cropRight").performScrollTo().performTextReplacement("0")
        ui.onNodeWithText("Apply").assertIsNotEnabled()
        ui.onNodeWithTag("page.cropRight").performTextReplacement("80")
        ui.onNodeWithTag("page.cropBottom").performScrollTo().performTextReplacement("60")
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) =
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                    .bufferedReader()
                    .readText()
                    .trim()
            }
        val keyboardSetting = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            ui.onNodeWithTag("page.cropBottom").performScrollTo().performClick()
            ui.waitUntil(5_000) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.hasWindowFocus() &&
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
            }
            ui.waitUntil(5_000) {
                val window =
                    android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {
                        it.hasWindowFocus()
                    }
                val ime =
                    window?.let {
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)
                            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                            ?.bottom
                    }
                window != null &&
                    ime != null &&
                    ui.onNodeWithTag("page.cropBottom").fetchSemanticsNode().boundsInRoot.bottom <=
                        window.height - ime
            }
            capture("scanner-crop-keyboard")
            ui.runOnIdle {
                val focused =
                    android.view.inspector.WindowInspector.getGlobalWindowViews().single {
                        it.hasWindowFocus()
                    }
                com.google.android.apps.common.testing.accessibility.framework.integrations.espresso
                    .AccessibilityValidator()
                    .setThrowExceptionFor(
                        com.google.android.apps.common.testing.accessibility.framework
                            .AccessibilityCheckResult
                            .AccessibilityCheckResultType
                            .ERROR
                    )
                    .check(focused)
            }
        } finally {
            if (keyboardSetting == "null")
                shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $keyboardSetting")
            shell("input keyevent 4")
        }

        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("page.cropRight").assertTextContains("80")
        ui.onNodeWithTag("page.cropBottom").assertTextContains("60")
        ui.onNodeWithText("Apply").performClick()
        ui.onNodeWithText("Use crop").performClick()
        assertNull(stored("Café receipt").pages.single().settings.crop)
        ui.onNodeWithTag("page.save").performClick()
        ui.waitUntil(10_000) { stored("Café receipt").pages.single().settings.crop != null }
        val saved = stored("Café receipt")
        assertEquals(2L, saved.version)
        assertEquals(0.8, requireNotNull(saved.pages.single().settings.crop).right, 0.005)
        assertEquals(0.6, requireNotNull(saved.pages.single().settings.crop).bottom, 0.005)
        assertArrayEquals(bytes, file.readBytes())
        ui.onNodeWithTag("scanner.page.1").performScrollTo().performClick()
        ui.onNodeWithTag("page.reset").performScrollTo().performClick()
        ui.onNodeWithTag("page.save").performClick()
        ui.waitUntil(10_000) { stored("Café receipt").pages.single().settings.crop == null }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun settingsReachBackupAndTrashWithoutScrollingTheLibrary() {
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithText("Backup & restore").assertIsDisplayed()
        ui.onNodeWithText("Recently Deleted").assertIsDisplayed()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        capture("coverage-scanner-settings")
        ui.disableAccessibilityChecks()
        ui.onNodeWithText("Backup & restore").performClick()
        ui.onNodeWithTag("backup.export").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Recently Deleted").performClick()
        ui.onNodeWithText("Recently Deleted", substring = false).assertExists()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Your documents").assertIsDisplayed()
    }

    @Test
    fun m3ReviewPageOrdering() {
        open("Tax form 2026")
        ui.onNodeWithTag("scanner.arrange").performScrollTo().performClick()
        ui.onNodeWithText("Arrange pages").assertExists()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        capture("m3-scanner-ordering")
        val original = stored("Tax form 2026").pages.map { it.id }
        ui.onNodeWithTag("arrange.options.2").performClick()
        ui.onNodeWithText("Move later").assertIsNotEnabled()
        ui.onNodeWithText("Move earlier").performClick()
        assertEquals(original, stored("Tax form 2026").pages.map { it.id })
        ui.onNodeWithTag("editor-cancel").performClick()
        ui.onNodeWithText("Keep editing").performClick()
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(5_000) { stored("Tax form 2026").pages.map { it.id } == original.reversed() }
    }

    @Test
    fun cameraCaptureSurvivesRotationBeforeSaving() {
        val automation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(
            ui.activity.packageName,
            android.Manifest.permission.CAMERA,
        )
        ui.onNodeWithTag("scanner.scan").performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("scanner.viewfinder").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("scanner.shutter").performClick()
        ui.waitUntil(15_000) { ui.onAllNodesWithText("Save 1").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Save 1").assertExists()
        capture("scanner-camera-draft")
        // A trial can expire while the camera is open. Failure must keep the captured page.
        if (trialApplies) {
            val prefs = ui.activity.getSharedPreferences("seven-trial", 0)
            prefs.edit().putLong("start", System.currentTimeMillis() - 8 * 86_400_000L).commit()
            ui.runOnUiThread { model.store.refresh() }
            ui.waitUntil(5_000) { !model.store.paidFeaturesAvailable }
            ui.onNodeWithTag("scanner.capture.save").performClick()
            ui.onNodeWithTag("scanner.error.ok").performClick()
            ui.onNodeWithText("Save 1").assertIsEnabled()
            prefs.edit().clear().commit()
            ui.runOnUiThread {
                model.store.access.startTrial()
                model.store.refresh()
            }
            ui.waitUntil(5_000) { model.store.paidFeaturesAvailable }
        }
        ui.onNodeWithTag("scanner.capture.save").performClick()
        ui.waitUntil(30_000) {
            model.documents.value.any { it.title.startsWith("Scan ") } || model.error.value != null
        }
        assertNull("Camera save failed: ${model.error.value}", model.error.value)
        assertTrue(model.documents.value.first { it.title.startsWith("Scan ") }.pages.isNotEmpty())
    }

    @Test
    fun pageBackProtectsEditsAndDiscardLeavesOriginalSettings() {
        open("Café receipt")
        ui.onNodeWithTag("scanner.page.1").performClick()
        ui.onNodeWithTag("page.rotate").performClick()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Keep editing").performClick()
        ui.onNodeWithTag("page.save").assertIsEnabled()
        capture("scanner-page-editor")
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Discard", substring = false).performClick()
        assertEquals(0L, stored("Café receipt").pages.single().settings.quarterTurns)
    }

    @Test
    fun visiblePageMenuRemovesOnlyTheConfirmedPage() {
        open("Tax form 2026")
        ui.onNodeWithTag("scanner.page.options.2").performScrollTo().performClick()
        ui.onNodeWithText("Remove page").performClick()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(2, stored("Tax form 2026").pages.size)
        ui.onNodeWithTag("scanner.page.options.2").performClick()
        ui.onNodeWithText("Remove page").performClick()
        ui.onNodeWithText("Remove page", substring = false).performClick()
        ui.waitUntil(5_000) { stored("Tax form 2026").pages.size == 1 }
    }

    @Test
    fun searchFindsRecognizedTextAndDetailsPersist() {
        ui.onNodeWithText("Café receipt").assertExists()
        ui.onNodeWithTag("scanner.search").performTextInput("Harbour")
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("scanner.document").fetchSemanticsNodes().size == 1
        }
        open("Apartment lease")
        ui.onNodeWithTag("scanner.title").performScrollTo().performTextReplacement("Lease, signed")
        ui.onNodeWithTag("scanner.saveDetails").performScrollTo().performClick()
        // Persistence precedes the library refresh. Recreate only after the save completes.
        ui.waitUntil(5_000) {
            model.documents.value.any { it.title == "Lease, signed" } &&
                ui.onAllNodes(hasTestTag("scanner.saveDetails") and isNotEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        assertEquals("Signed copy. Original in the blue folder.", stored("Lease, signed").note)
        // Recreation keeps the screen and the saved name.
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("scanner.title").assertTextContains("Lease, signed")
    }

    @Test
    fun pageRotationIsSavedNonDestructively() {
        open("Café receipt")
        val before = model.repository.pageFiles(stored("Café receipt")).single().readBytes()
        ui.onNodeWithTag("scanner.page.1").performScrollTo().performClick()
        ui.onNodeWithTag("page.rotate").performClick()
        ui.onNodeWithTag("page.save").performClick()
        ui.waitUntil(5_000) { stored("Café receipt").pages[0].settings.quarterTurns == 1L }
        assertArrayEquals(
            before,
            model.repository.pageFiles(stored("Café receipt")).single().readBytes(),
        )
    }

    @Test
    fun correctedTextPersistsThroughRotationWithoutChangingImage() {
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

        open("Café receipt")
        val original = stored("Café receipt")
        val before = model.repository.pageFiles(original).single().readBytes()
        ui.onNodeWithTag("scanner.page.1").performScrollTo().performClick()
        ui.onNodeWithTag("page.editText").performScrollTo().performClick()
        val corrected = "Café receipt\nCorrected total: 42.00"
        ui.onNodeWithTag("page.correctedText").performTextReplacement(corrected)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("page.correctedText").assertTextContains(corrected)
        capture("scanner-text-correction")
        ui.onNodeWithTag("editor-save").performClick()
        // Wait for the editor to finish refreshing and release its repository operation before
        // acquiring that lock below. A file on disk alone is not a completed UI save.
        ui.waitUntil(5_000) {
            model.textCorrection.value == null &&
                model.documents.value.any {
                    it.id == original.id && it.pages.single().text == corrected
                }
        }
        val after = stored("Café receipt")
        assertEquals(original.pages.single().settings, after.pages.single().settings)
        assertArrayEquals(before, model.repository.pageFiles(after).single().readBytes())
        runBlocking {
            model.repository.io {
                fillRecognizedText(
                    original.id,
                    mapOf(original.pages.single().id to "late OCR"),
                    overwrite = true,
                    expectedPages = original.pages,
                )
            }
        }
        assertEquals(corrected, stored("Café receipt").pages.single().text)
    }

    @Test
    fun exportPreparesASearchablePdf() {
        open("Tax form 2026")
        ui.onNodeWithTag("scanner.export").performClick()
        ui.onNodeWithTag("export.page.2").performClick()
        ui.onNodeWithText("1 of 2 pages").assertExists()
        ui.onNodeWithTag("export.prepare").performClick()
        ui.waitUntil(30_000) {
            ui.onAllNodesWithTag("export.size").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Tax form 2026 - selected pages.pdf").assertExists()
        ui.onNodeWithTag("export.share").assertExists()
        capture("scanner-export")
        ui.onNodeWithTag("export.preview").performScrollTo().performClick()
        ui.onNodeWithTag("pdf.preview").assertExists()
        capture("scanner-pdf-preview")
        ui.onNodeWithTag("pdf.close").performClick()
        ui.onNodeWithTag("export.share").assertIsDisplayed()
    }

    @Test
    fun trashWithUndoAndRestoreFromRecentlyDeleted() {
        card("Washer warranty").performTouchInput { longClick() }
        ui.onNodeWithText("Move to Recently Deleted").performClick()
        ui.waitUntil(5_000) {
            model.repository.list(deleted = true).any { it.title == "Washer warranty" }
        }
        ui.onNodeWithTag("scanner.library").performScrollToNode(hasText("Recently Deleted"))
        ui.onNodeWithText("Recently Deleted").performClick()
        ui.onNodeWithText("Washer warranty").assertExists()
        capture("coverage-scanner-trash")
        ui.onAllNodesWithTag("trash.restore")[0].performClick()
        // Deleted documents are listed newest first by creation: the warranty (45 days) follows the
        // bill.
        val first = model.deleted.value.first().title
        ui.waitUntil(5_000) { model.repository.list().any { it.title == first } }
        assertEquals(1, model.repository.list(deleted = true).size)
    }

    @Test
    fun expiredTrialOffersTheUnlockForNewPagesButKeepsExport() {
        assumeTrue("Access cannot lapse in this build", trialApplies)
        ui.activity.application
            .getSharedPreferences("seven-trial", 0)
            .edit()
            .putLong("start", System.currentTimeMillis() - 8 * 86_400_000L)
            .commit()
        model.store.refresh()
        ui.waitForIdle()
        ui.onNodeWithTag("scanner.import").performClick()
        ui.onNodeWithText("Import documents is part of the unlock.").assertExists()
        ui.onNodeWithText("Not now").performClick()
        open("Café receipt")
        ui.onNodeWithTag("scanner.export").assertIsEnabled()
    }

    @Test
    fun capturedPagesAreSavedThenRecognized() {
        val dir = File(ui.activity.cacheDir, "ui-capture").apply { mkdirs() }
        val photo =
            File(dir, "1.jpg").also {
                Processing.normalize(
                    Demo.page(
                        "SEVENS SCANNER\nReceipt page 1\nCoffee 120\nTotal 120",
                        plain = true,
                    ),
                    it,
                )
            }
        ui.runOnUiThread { model.capture(listOf(Uri.fromFile(photo))) { dir.deleteRecursively() } }
        ui.waitUntil(30_000) {
            !dir.exists() &&
                model.documents.value.any { d ->
                    d.title.startsWith("Scan ") && d.pages.single().text.contains("SEVENS")
                }
        }
        assertFalse(dir.exists())
    }
}
