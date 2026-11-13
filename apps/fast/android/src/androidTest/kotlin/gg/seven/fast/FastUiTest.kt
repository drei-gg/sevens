package gg.seven.fast

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.fast.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepare() {
        ui.waitUntil(10_000) {
            (ui.activity.application as FastApplication).repository.state.value != null
        }
        if (ui.onAllNodesWithText("Look around").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithText("Look around").performClick()
        val app = ui.activity.application as FastApplication
        app.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        app.access.startTrial()
        runBlocking { app.repository.update { FastArchive() } }
        ui.waitForIdle()
    }

    @Test
    fun startEndAndJournalPersistThroughRecreation() {
        ui.onNodeWithText("Start fast").performScrollTo().performClick()
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        ui.onNodeWithText("End fast").performScrollTo().performClick()
        ui.onNodeWithContentDescription("What did you notice? What would you change?")
            .performScrollTo()
            .performTextInput("A shorter fast is still a record")
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) {
            (ui.activity.application as FastApplication).repository.state.value!!.active == null
        }
        ui.onNodeWithTag("nav-Journal").performClick()
        ui.onNodeWithText("Add journal entry").performClick()
        ui.onNodeWithText("Mood", useUnmergedTree = true).performScrollTo().performClick()
        ui.onNodeWithText("5 · Good").performClick()
        ui.onNodeWithContentDescription("What’s happening in your body, your day, or your thoughts?")
            .performScrollTo()
            .performTextInput("Native Android check-in")
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) {
            (ui.activity.application as FastApplication)
                .repository
                .state
                .value!!
                .journal
                .isNotEmpty()
        }
        Assert.assertEquals(
            5,
            (ui.activity.application as FastApplication)
                .repository
                .state
                .value!!
                .journal
                .single()
                .mood,
        )
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Native Android check-in").assertIsDisplayed()
    }

    @Test
    fun expiredTrialAllowsEndingAndEditingButRejectsNewRecords() {
        val app = ui.activity.application as FastApplication
        val f = FastRecord(start = nowMillis() - 3_600_000)
        runBlocking { app.repository.update { FastArchive(fasts = listOf(f)) } }
        app.getSharedPreferences("seven-trial", 0)
            .edit()
            .putLong("start", System.currentTimeMillis() - 8 * 86_400_000L)
            .commit()
        ui.onNodeWithText("End fast").performScrollTo().performClick()
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.active == null }
        ui.onNodeWithText("Start fast").performScrollTo().performClick()
        ui.onNodeWithText("Yours, paid once").assertIsDisplayed()
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithTag("nav-History").performClick()
        ui.onNodeWithText("List", useUnmergedTree = true).performClick()
        ui.onNodeWithText("1 hr", substring = true).performScrollTo().performClick()
        ui.onNodeWithText("Edit fast").performClick()
        ui.onNodeWithContentDescription("What did you notice? What would you change?")
            .performScrollTo()
            .performTextInput("Edited after expiry")
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        try {
            ui.waitUntil(10_000) {
                app.repository.state.value!!.fasts.single().reflection == "Edited after expiry"
            }
        } catch (e: Exception) {
            ui.onRoot(useUnmergedTree = true).printToLog("FastUiFailure")
            println("Saved reflection: " + app.repository.state.value!!.fasts.single().reflection)
            throw e
        }
    }

    @Test
    fun waterQuickAddUndoAndDraftSurviveRotation() {
        val app = ui.activity.application as FastApplication
        ui.onNodeWithText("250 mL").performScrollTo().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.metrics.size == 1 }
        ui.onNodeWithText("Undo last glass").performScrollTo().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.metrics.isEmpty() }
        ui.onNodeWithTag("today-check-in").performScrollTo().performClick()
        ui.onNodeWithContentDescription("What’s happening in your body, your day, or your thoughts?")
            .performScrollTo()
            .performTextInput("Keep this draft")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Keep this draft").assertExists()
        ui.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        ui.onNodeWithText("Discard draft").performClick()
    }

    @Test
    fun waterGoalUsesDisplayUnitsWithoutChangingTheGlass() {
        val app = ui.activity.application as FastApplication
        runBlocking {
            app.repository.update { it.copy(settings = it.settings.copy(useFluidOunces = true)) }
        }
        ui.onNodeWithText("Set a daily goal").performScrollTo().performClick()
        ui.onNodeWithText("Save").assertIsDisplayed()
        ui.onNodeWithText("Use a personal goal").performClick()
        ui.onNodeWithContentDescription("Daily amount").performScrollTo().performTextInput("20")
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.settings.water.goalMilliliters != null }
        Assert.assertEquals(
            20 * 29.5735295625,
            app.repository.state.value!!.settings.water.goalMilliliters!!,
            0.001,
        )
        Assert.assertEquals(
            250.0,
            app.repository.state.value!!.settings.water.quickAddMilliliters,
            0.0,
        )
    }

    @Test
    fun waterQuickAddUsesSelectedDayAndPreservesUndo() {
        val app = ui.activity.application as FastApplication
        ui.onNodeWithText("Water today").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Previous day").performClick()
        ui.onNodeWithText("250 mL").performScrollTo().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.metrics.size == 1 }
        val saved = app.repository.state.value!!.metrics.single()
        Assert.assertEquals(
            java.time.LocalDate.now().minusDays(1),
            saved.date.instant().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
        )
        ui.onNodeWithText("Undo last glass").performScrollTo().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.metrics.isEmpty() }
    }
}
