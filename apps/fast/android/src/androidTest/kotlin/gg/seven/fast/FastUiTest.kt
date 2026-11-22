package gg.seven.fast

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.fast.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

/**
 * Runs on every flavor. The generic build is unlocked (no trial), so the trial gate is exercised by
 * the personal flavor's FastTrialTest (src/androidTestPersonal).
 */
@RunWith(AndroidJUnit4::class)
class FastUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepare() {
        ui.waitUntil(10_000) {
            (ui.activity.application as FastApplication).repository.state.value != null
        }
        val app = ui.activity.application as FastApplication
        // SevenKit's launch sheets: Before you buy on a first launch, or the trial-ended sheet.
        if (ui.onAllNodesWithTag("seven-look-around").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("seven-look-around").performClick()
        if (ui.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithText("Not now").performClick()
        app.store.beforeYouBuyShown = true
        app.store.trialEndedShown = true
        app.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        app.access.startTrial()
        runBlocking { app.repository.update { FastArchive.empty() } }
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
    fun activeTimerCardsFollowTheDeviceTimeFormat() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String =
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                    .bufferedReader()
                    .readText()
                    .trim()
            }
        val original = shell("settings get system time_12_24")
        val start =
            java.time.LocalDate.of(2026, 11, 17)
                .atTime(19, 5)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
                .toDouble()
        val app = ui.activity.application as FastApplication
        runBlocking {
            app.repository.update {
                FastArchive.empty().copy(fasts = listOf(fastRecord(start = start)))
            }
        }
        try {
            shell("settings put system time_12_24 12")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("Tue · 7:05 PM").performScrollTo().assertIsDisplayed()
            capture("fast-primary-time-12")
            shell("settings put system time_12_24 24")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("Tue · 19:05").performScrollTo().assertIsDisplayed()
            capture("fast-primary-time-24")
        } finally {
            shell(
                if (original == "null") "settings delete system time_12_24"
                else "settings put system time_12_24 $original"
            )
        }
    }

    @Test
    fun m3ReviewRatings() {
        ui.onNodeWithTag("nav-Journal").performClick()
        ui.onNodeWithText("Add journal entry", useUnmergedTree = true).performClick()
        ui.onNodeWithContentDescription("Mood 5").performScrollTo().performClick()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        capture("m3-fast-ratings")
        ui.onNodeWithContentDescription("Clear Mood").performClick()
        ui.onNodeWithContentDescription("Mood 5").assertIsNotSelected()
        ui.onNode(hasSetTextAction() and hasText("Observation"))
            .performScrollTo()
            .performTextInput("Explicitly unrecorded mood")
        ui.onNodeWithTag("editor-save").performClick()
        val app = ui.activity.application as FastApplication
        ui.waitUntil(5_000) { app.repository.state.value!!.journal.isNotEmpty() }
        Assert.assertNull(app.repository.state.value!!.journal.single().mood)
    }

    @Test
    fun historySearchFindsRecordsOutsideTheSelectedCalendarDay() {
        val app = ui.activity.application as FastApplication
        val old =
            fastRecord(
                start = nowMillis() - 10 * 86_400_000.0,
                end = nowMillis() - 9 * 86_400_000.0,
                intention = "Trip preparation",
            )
        runBlocking { app.repository.update { it.copy(fasts = listOf(old)) } }
        ui.onNodeWithTag("nav-History").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Trip preparation")
        ui.onNode(
                hasText("Trip preparation", substring = true) and
                    hasClickAction() and
                    !hasSetTextAction()
            )
            .assertExists()
        ui.onNodeWithText("A history worth keeping").assertDoesNotExist()
    }

    @Test
    fun plansAndPresetsSaveAndRemainEditable() {
        val app = ui.activity.application as FastApplication
        ui.onNodeWithText("Plans & saved timers").performScrollTo().performClick()
        ui.onNodeWithText("Plan a fast").performClick()
        ui.onNode(hasSetTextAction() and hasText("Intention"))
            .performScrollTo()
            .performTextInput("Tomorrow's plan")
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(5_000) { app.repository.state.value!!.plans.size == 1 }
        ui.onNodeWithText("Save a custom preset").performClick()
        ui.onNode(hasSetTextAction() and hasText("Preset name")).performTextInput("Flexible day")
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(5_000) { app.repository.state.value!!.presets.size == 1 }
        capture("fast-plans")
        ui.onNodeWithText("Flexible day").performClick()
        ui.onNode(hasSetTextAction() and hasText("Preset name"))
            .performTextReplacement("Changed draft")
        ui.onNodeWithTag("editor-cancel").performClick()
        ui.onNodeWithText("Discard draft").performClick()
        Assert.assertEquals("Flexible day", app.repository.state.value!!.presets.single().name)
        ui.onNodeWithTag("nav-History").performClick()
        capture("fast-history")
        ui.onNodeWithTag("nav-Trends").performClick()
        capture("fast-trends")
    }

    @Test
    fun startEndAndJournalPersistThroughRecreation() {
        ui.onNodeWithText("Start fast").performScrollTo().performClick()
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        ui.onNodeWithText("End fast").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("Reflection"))
            .performScrollTo()
            .performTextInput("A shorter fast is still a record")
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) {
            (ui.activity.application as FastApplication).repository.state.value!!.active == null
        }
        ui.onNodeWithTag("nav-Journal").performClick()
        ui.onNodeWithText("Add journal entry", useUnmergedTree = true).performClick()
        ui.onNodeWithContentDescription("Mood 5").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("Observation"))
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
    fun waterQuickAddUndoAndDraftSurviveRotation() {
        val app = ui.activity.application as FastApplication
        ui.onNodeWithText("250 mL").performScrollTo().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.metrics.size == 1 }
        ui.onNodeWithText("Water today").performScrollTo()
        capture("coverage-fast-water")
        ui.onNodeWithText("Undo last glass").performScrollTo().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.metrics.isEmpty() }
        ui.onNodeWithText("Check in").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("Observation"))
            .performScrollTo()
            .performTextInput("Keep this draft")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Keep this draft").assertExists()
        ui.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
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
        ui.onNode(hasSetTextAction() and hasText("Daily amount"))
            .performScrollTo()
            .performTextInput("20")
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
            saved.date.atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
        )
        ui.onNodeWithText("Undo last glass").performScrollTo().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.metrics.isEmpty() }
    }
}
