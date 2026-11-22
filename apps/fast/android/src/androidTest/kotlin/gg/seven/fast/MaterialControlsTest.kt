package gg.seven.fast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import gg.seven.kit.*
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class MaterialControlsTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun formSystemBarsFollowLightAndDarkSurfaces() {
        var dark by mutableStateOf(false)
        var window: android.view.Window? = null
        var dialogView: android.view.View? = null
        ui.setContent {
            androidx.compose.material3.MaterialTheme(
                colorScheme = if (dark) SevenSchemes.dark else SevenSchemes.light
            ) {
                FullScreenForm("Theme review", onClose = {}, onSave = {}) {
                    val view = androidx.compose.ui.platform.LocalView.current
                    SideEffect {
                        dialogView = view
                        window =
                            (view.parent as androidx.compose.ui.window.DialogWindowProvider).window
                    }
                    Text("Form content")
                }
            }
        }
        fun assertBars(light: Boolean) {
            ui.runOnIdle {
                val bars =
                    androidx.core.view.WindowCompat.getInsetsController(window!!, dialogView!!)
                org.junit.Assert.assertEquals(light, bars.isAppearanceLightStatusBars)
                org.junit.Assert.assertEquals(light, bars.isAppearanceLightNavigationBars)
            }
        }
        assertBars(true)
        ui.runOnIdle { dark = true }
        assertBars(false)
        ui.runOnIdle { dark = false }
        assertBars(true)
    }

    @Test
    fun intervalEntryRejectsInvalidValuesAndCommitsOneValidValue() {
        var committed = 5
        ui.setContent {
            SevenTheme {
                var value by remember { mutableIntStateOf(5) }
                IntegerSetting("Interval in minutes", value, 5..720, step = 5) {
                    value = it
                    committed = it
                }
            }
        }
        ui.onNodeWithContentDescription("Edit Interval in minutes").performClick()
        ui.onNodeWithTag("integer-setting").performTextReplacement("723")
        ui.onNodeWithText("Set").assertIsNotEnabled()
        ui.onNodeWithTag("integer-setting").performTextReplacement("360")
        ui.onNodeWithText("Set").performClick()
        org.junit.Assert.assertEquals(360, committed)
        ui.onNodeWithText("360").assertExists()
    }

    @Test
    fun pickerHasAccessibleClickAndClockKeyboardModes() {
        ui.setContent {
            SevenTheme {
                Column(Modifier.safeDrawingPadding()) {
                    DateField("Date", LocalDate.of(2026, 11, 17)) {}
                    TimeField("Reminder", 8, 30) { _, _ -> }
                }
            }
        }
        ui.onNodeWithContentDescription("Date,", substring = true)
            .assertHasClickAction()
            .performClick()
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithContentDescription("Reminder,", substring = true)
            .assertHasClickAction()
            .performClick()
        screenshot("material-time-clock")
        ui.onNodeWithContentDescription("Type time").performClick()
        ui.onNodeWithContentDescription("Use clock").assertExists()
        screenshot("material-time-keyboard")
        ui.onNodeWithText("OK").assertIsDisplayed().performClick()
    }

    @Test
    fun largeTextChoicesWrapAndKeepRadioSelection() {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SevenTheme {
                    var chosen by remember { mutableStateOf(false) }
                    Box(Modifier.safeDrawingPadding().width(280.dp)) {
                        ConnectedChoice(
                            listOf(false to "Quiet reminder", true to "Alarm"),
                            chosen,
                        ) {
                            chosen = it
                        }
                    }
                }
            }
        }
        ui.onNodeWithTag("choice-Quiet reminder").assertIsDisplayed().assertIsSelected()
        ui.onNodeWithTag("choice-Alarm").performClick().assertIsSelected()
        ui.onNodeWithTag("choice-Quiet reminder").assertIsNotSelected()
        screenshot("material-choices-large-text")
    }

    @Test
    fun inlineSearchClearsWithoutLeavingTheScreen() {
        ui.setContent {
            SevenTheme {
                val state = rememberTextFieldState()
                Column(Modifier.safeDrawingPadding()) {
                    SearchInput("Search records", state = state)
                    Text("Matches: ${state.text}")
                }
            }
        }
        ui.onNode(hasSetTextAction()).performTextInput("coffee")
        ui.onNodeWithText("Matches: coffee").assertExists()
        ui.onNode(hasSetTextAction()).performImeAction()
        ui.onNodeWithContentDescription("Clear search").performClick()
        ui.onNodeWithText("Matches: ").assertExists()
        ui.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io
            .File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
            .outputStream()
            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
