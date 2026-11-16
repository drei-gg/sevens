package gg.seven.bloom

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BloomTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encryptedJournalPersistsAndRejectsTampering(): Unit = runBlocking {
        val name = "test-${UUID.randomUUID()}"
        val repo = BloomRepository(context, name)
        val row =
            newRecord("day")
                .put("flow", "light")
                .put("period_start", true)
                .put("notes", "private sentinel")
        val saved = repo.change(command("day", row, 0), true)
        assertFalse(repo.file.readBytes().toString(Charsets.UTF_8).contains("private sentinel"))
        assertEquals(saved, BloomRepository(context, name).read())
        val bytes =
            repo.file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        repo.file.writeBytes(bytes)
        try {
            BloomRepository(context, name).read()
            fail("Authenticated storage must reject tampering")
        } catch (expected: java.security.GeneralSecurityException) {
            /* keep original intact */
        }
        assertArrayEquals(bytes, repo.file.readBytes())
        repo.file.delete()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry("bloom.records.$name.v1")
        }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun planAndDoseUseSharedScheduling() {
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        for (label in listOf("Done", "Look around first")) {
            if (ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
                ui.onNodeWithText(label).performClick()
        }
        ui.waitForIdle()
        screenshot("android-today")
        ui.onNodeWithText("Care").performClick()
        ui.onNodeWithText("Add pill plan").performClick()
        ui.onNodeWithText("Plan name").performTextReplacement("Android pill fixture")
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Add pill plan").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onAllNodesWithText("Taken").onLast().performScrollTo().performClick()
        ui.waitUntil(10000) {
            runBlocking {
                JSONObject(BloomRepository(context).read()).rows("doses").any {
                    it.getString("status") == "taken"
                }
            }
        }
        screenshot("android-care")
    }

    @Test
    fun editsSurviveActivityRecreationAndSaveThroughRust() {
        // Run on the explicitly unlocked personal fixture; production gates remain in SevenKit.
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        ui.waitForIdle()
        for (label in listOf("Done", "Look around first")) {
            if (ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
                ui.onNodeWithText(label).performClick()
        }
        ui.waitForIdle()
        ui.onNodeWithText("Log today").performClick()
        ui.onNodeWithText("Notes").performScrollTo().performTextReplacement("Android journal smoke")
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Android journal smoke").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Android journal smoke").assertExists()
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Journal").performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Android journal smoke").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Android journal smoke").assertExists()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Android journal smoke").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Android journal smoke").assertExists()
        screenshot("android-journal")
        val persisted = runBlocking { JSONObject(BloomRepository(context).read()) }
        assertTrue(persisted.rows("days").any { it.getString("notes") == "Android journal smoke" })
    }
}
