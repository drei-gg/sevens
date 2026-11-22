package gg.seven.bloom

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.bloom.core.bloomHealthReview
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HealthFlowUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun consentCancellationAndReviewedImport() {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("featureOrientation") == "landscape") {
            ui.runOnUiThread { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(5_000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        }

        ui.waitUntil(15_000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        for (label in listOf("Done", "Look around first")) {
            if (ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
                ui.onNodeWithText(label).performClick()
        }
        lateinit var model: BloomModel
        ui.runOnIdle { model = ViewModelProvider(ui.activity)[BloomModel::class.java] }
        ui.waitUntil(10_000) { !model.busy.value }
        val before = model.archive.value!!
        ui.onNodeWithText("Settings", substring = false).performClick()
        ui.onNodeWithText("Share journal flow").performScrollTo().performClick()
        ui.onNodeWithText("Share flow to Health Connect?").assertIsDisplayed()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(before, model.archive.value)
        val day = LocalDate.now().minusYears(20).toString()
        val samples =
            JSONArray()
                .put(JSONObject().put("day", day).put("flow", "medium").put("period_start", false))
        val preview = bloomHealthReview(before, samples.toString())
        val id = JSONObject(preview).rows("days").single().getString("id")
        try {
            ui.runOnIdle { model.healthPreview.value = preview }
            ui.onNodeWithText("Review flow").assertIsDisplayed()
            ui.onNodeWithText(day).assertIsDisplayed()
            ui.enableAccessibilityChecks()
            ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val screenshot =
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "feature-health-review.png")
                .outputStream()
                .use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            ui.onNodeWithText("Import", substring = false).performClick()
            ui.waitUntil(10_000) { model.healthPreview.value == null && !model.busy.value }
            assertEquals(
                "medium",
                JSONObject(runBlocking { model.repository.read() })
                    .rows("days")
                    .single { it.getString("id") == id }
                    .getString("flow"),
            )
            assertTrue(model.healthNote.value.startsWith("Reviewed flow days imported."))
        } finally {
            runBlocking {
                val current = JSONObject(model.repository.read())
                if (current.rows("days").any { it.getString("id") == id })
                    model.repository.change(
                        command("delete_day", null, current.getLong("revision"), id),
                        true,
                    )
            }
        }
    }
}
