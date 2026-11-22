package gg.seven.fast

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.fast.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

/**
 * The trial gate, on the personal flavor: with `sevens.personalUnlock=false` (the default) it has a
 * real seven-day trial, while the generic build is unlocked and has none. Run with
 * `:fast:connectedPersonalDebugAndroidTest -Psevens.brandDir=…`.
 */
@RunWith(AndroidJUnit4::class)
class FastTrialTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepare() {
        Assume.assumeFalse("An unlocked personal build has no trial", BuildConfig.PERSONAL_UNLOCK)
        ui.waitUntil(10_000) {
            (ui.activity.application as FastApplication).repository.state.value != null
        }
        val app = ui.activity.application as FastApplication
        if (ui.onAllNodesWithTag("seven-look-around").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("seven-look-around").performClick()
        if (ui.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithText("Not now").performClick()
        app.store.beforeYouBuyShown = true
        app.store.trialEndedShown = true
        ui.waitForIdle()
    }

    @Test
    fun expiredTrialAllowsEndingAndEditingButRejectsNewRecords() {
        val app = ui.activity.application as FastApplication
        val f = fastRecord(start = nowMillis() - 3_600_000)
        runBlocking { app.repository.update { FastArchive.empty().copy(fasts = listOf(f)) } }
        app.getSharedPreferences("seven-trial", 0)
            .edit()
            .clear()
            .putLong("start", System.currentTimeMillis() - 8 * 86_400_000L)
            .commit()
        ui.onNodeWithText("End fast").performScrollTo().performClick()
        ui.onNodeWithText("Save").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) { app.repository.state.value!!.active == null }
        ui.onNodeWithText("Start fast").performScrollTo().performClick()
        ui.onNodeWithText("What the unlock adds").assertIsDisplayed()
        ui.onNodeWithText("Not now").performClick()
        ui.onNodeWithTag("nav-History").performClick()
        ui.onNodeWithText("List", useUnmergedTree = true).performClick()
        ui.onNodeWithText("1 hr", substring = true).performScrollTo().performClick()
        ui.onNodeWithText("Edit fast").performClick()
        ui.onNode(hasSetTextAction() and hasText("Reflection"))
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
}
