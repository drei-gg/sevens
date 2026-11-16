package gg.seven.scanner

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.scanner.core.ScanDocument
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main flows, driven through the UI on the demo library (iOS ScannerUITests). */
@RunWith(AndroidJUnit4::class)
class ScannerUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private lateinit var model: ScannerModel

    @Before
    fun prepare() {
        ui.waitForIdle()
        if (ui.onAllNodesWithTag("seven-look-around").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("seven-look-around").performClick()
        model = ViewModelProvider(ui.activity)[ScannerModel::class.java]
        ui.waitUntil(10_000) { model.loaded.value }
        val app = ui.activity.application
        app.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        model.store.startTrial()
        File(app.filesDir, ScannerModel.LIBRARY).deleteRecursively()
        Demo.demo(app, model.repository)
        runBlocking { model.reload() }
        ui.waitForIdle()
    }

    private fun stored(title: String): ScanDocument = model.repository.list().first { it.title == title }

    private fun card(title: String): SemanticsNodeInteraction {
        val matcher = hasTestTag("scanner.document") and hasContentDescription(title, substring = true)
        ui.onNodeWithTag("scanner.library").performScrollToNode(matcher)
        return ui.onNode(matcher)
    }

    private fun open(title: String) {
        card(title).performClick()
        ui.waitForIdle()
    }

    @Test
    fun searchFindsRecognizedTextAndDetailsPersist() {
        ui.onNodeWithText("Café receipt").assertExists()
        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("scanner.search"))).performTextInput("Harbour")
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("scanner.document").fetchSemanticsNodes().size == 1 }
        open("Apartment lease")
        ui.onNodeWithTag("scanner.title").performScrollTo().performTextReplacement("Lease, signed")
        ui.onNodeWithTag("scanner.saveDetails").performScrollTo().performClick()
        ui.waitUntil(5_000) { runCatching { stored("Lease, signed") }.isSuccess }
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
        assertArrayEquals(before, model.repository.pageFiles(stored("Café receipt")).single().readBytes())
    }

    @Test
    fun exportPreparesASearchablePdf() {
        open("Tax form 2026")
        ui.onNodeWithTag("scanner.export").performClick()
        ui.onNodeWithTag("export.page.2").performClick()
        ui.onNodeWithText("1 of 2 pages").assertExists()
        ui.onNodeWithTag("export.prepare").performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("export.size").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Tax form 2026 - selected pages.pdf").assertExists()
        ui.onNodeWithTag("export.share").assertExists()
    }

    @Test
    fun trashWithUndoAndRestoreFromRecentlyDeleted() {
        card("Washer warranty").performTouchInput { longClick() }
        ui.onNodeWithText("Move to Recently Deleted").performClick()
        ui.waitUntil(5_000) { model.repository.list(deleted = true).any { it.title == "Washer warranty" } }
        ui.onNodeWithTag("scanner.library").performScrollToNode(hasText("Recently Deleted"))
        ui.onNodeWithText("Recently Deleted").performClick()
        ui.onNodeWithText("Washer warranty").assertExists()
        ui.onAllNodesWithTag("trash.restore")[0].performClick()
        // Deleted documents are listed newest first by creation: the warranty (45 days) follows the bill.
        val first = model.deleted.value.first().title
        ui.waitUntil(5_000) { model.repository.list().any { it.title == first } }
        assertEquals(1, model.repository.list(deleted = true).size)
    }

    @Test
    fun expiredTrialOffersTheUnlockForNewPagesButKeepsExport() {
        ui.activity.application.getSharedPreferences("seven-trial", 0).edit().putLong("start", System.currentTimeMillis() - 8 * 86_400_000L).commit()
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
        val photo = File(dir, "1.jpg").also { Processing.normalize(Demo.page("SEVENS SCANNER\nReceipt page 1\nCoffee 120\nTotal 120", plain = true), it) }
        ui.runOnUiThread { model.capture(listOf(Uri.fromFile(photo))) { dir.deleteRecursively() } }
        ui.waitUntil(30_000) { model.documents.value.any { d -> d.title.startsWith("Scan ") && d.pages.single().text.contains("SEVENS") } }
        assertFalse(dir.exists())
    }
}
