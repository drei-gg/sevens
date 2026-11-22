package gg.seven.qr

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trial gate, on the personal flavor: with `sevens.personalUnlock=false` (the default) it has a
 * real seven-day trial, while the generic build is unlocked and has none. Run with
 * `:qr:connectedPersonalDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class QrTrialTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private val model
        get() = ViewModelProvider(ui.activity)[QrModel::class.java]

    @Before
    fun prepare() {
        Assume.assumeFalse("An unlocked personal build has no trial", BuildConfig.PERSONAL_UNLOCK)
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(ui.activity.packageName, android.Manifest.permission.CAMERA)
        model.store.beforeYouBuyShown = true
        model.store.trialEndedShown = true
        ui.activity.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        runBlocking { model.clearHistory() }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10_000) {
            runCatching { ui.onAllNodesWithTag("scan.make").fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
    }

    @Test
    fun beforeTheTrialScansShowTheirVerdictButAreNotSaved() {
        model.pendingCode.value = textCode("http://paypa1-verify.help/login")
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("result.verdict").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("result.verdict")
            .assertContentDescriptionContains(ui.activity.getString(R.string.qr_verdict_scam), substring = true)
        assertTrue(model.historyState.value.items.isEmpty())
    }

    @Test
    fun makeBeforeTheTrialOffersTheUnlock() {
        ui.onNodeWithTag("scan.make").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("seven-unlock").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("seven-unlock").assertExists()
    }
}
