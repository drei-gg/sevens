package gg.seven.alarm

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.alarm.core.*
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main flows through the real screens, on an in-memory archive that never arms an alarm. */
@RunWith(AndroidJUnit4::class)
class AlarmUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app: AlarmApplication = ApplicationProvider.getApplicationContext()
    private lateinit var driver: PreviewAlarmDriver
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch(seed: LoopArchive = newLoopArchive(), trial: Boolean = true) {
        app.getSharedPreferences("seven-launch", Context.MODE_PRIVATE).edit().putBoolean("beforeYouBuy", true).commit()
        val start = if (trial) System.currentTimeMillis() else System.currentTimeMillis() - 8 * 86_400_000L
        app.getSharedPreferences("seven-trial", Context.MODE_PRIVATE).edit().putLong("start", start).putLong("lastSeen", System.currentTimeMillis()).commit()
        driver = PreviewAlarmDriver()
        app.engine = AlarmEngine(ArchiveRepository(null, seed), driver, isPreview = true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.waitUntil(10_000) { app.engine.ready.value }
    }

    @After
    fun close() {
        scenario?.close()
    }

    @Test
    fun createAnAlarmFromTheEditor() {
        launch()
        ui.onNodeWithText("No alarms yet").assertIsDisplayed()
        ui.onNodeWithTag("add-routine").performClick()
        ui.onNodeWithText("New alarm").assertIsDisplayed()
        ui.onNodeWithTag("routine-name").performTextReplacement("Early run")
        ui.onNodeWithTag("day-1").performScrollTo().performClick() // add Sunday
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.routines.isNotEmpty() }
        val saved = app.engine.archive.value.routines.single()
        assertEquals("Early run", saved.name)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), saved.slots.single().weekdays)
        assertEquals(1, driver.rows.size)
        ui.onNodeWithTag("routine-card-Early run").assertIsDisplayed()
        ui.onNodeWithText("Next alarm").assertIsDisplayed()
        // Recreation keeps the list (state is in the engine, the draft in the model).
        scenario!!.recreate()
        ui.onNodeWithTag("routine-card-Early run").assertIsDisplayed()
    }

    @Test
    fun aQuickNapStartsAOneOff() {
        launch()
        ui.onNodeWithTag("nap-20").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.routines.isNotEmpty() }
        val nap = app.engine.archive.value.routines.single()
        assertEquals(Timing.ONCE, nap.timing)
        assertEquals("20-minute nap", nap.name)
        ui.onNodeWithTag("routine-card-20-minute nap").assertIsDisplayed()
    }

    @Test
    fun anExpiredTrialRefusesNewAlarmsWithTheIosSentence() {
        launch(trial = false)
        ui.onNodeWithTag("nap-15").performClick()
        ui.onNodeWithText("Needs attention").assertIsDisplayed()
        ui.onNodeWithText("Start the trial or unlock in Settings to create or edit schedules. Existing enabled alarms keep running.").assertIsDisplayed()
        ui.onNodeWithText("OK").performClick()
        assertTrue(app.engine.archive.value.routines.isEmpty())
    }

    @Test
    fun theArithmeticPreviewCompletesWithoutRecording() {
        val wake = newRoutine(RoutineKind.ALARM, Instant.now()).copy(mission = MissionKind.ARITHMETIC, mathCount = 1)
        launch(newLoopArchive().copy(routines = listOf(wake)))
        ui.onNodeWithTag("try-mission").performScrollTo().performClick()
        ui.onNodeWithText("Mission preview. No alarm will ring.").assertIsDisplayed()
        ui.onNodeWithTag("mission-answer").performTextInput("1")
        ui.onNodeWithTag("check-answer").performClick()
        ui.onNodeWithText("Try that one again.").assertIsDisplayed()
        // The sum the screen shows is read from its accessibility label.
        val label = ui.onNode(hasContentDescription(" plus ", substring = true)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        val (left, right) = label.split(" plus ").map { it.trim().toInt() }
        ui.onNodeWithTag("mission-answer").performTextReplacement("${left + right}")
        ui.onNodeWithTag("check-answer").performClick()
        ui.onNodeWithText("Done.").assertIsDisplayed()
        assertTrue(app.engine.archive.value.events.isEmpty())
    }

    @Test
    fun aRingingAlarmsMissionRecordsCompletion() {
        val wake = newRoutine(RoutineKind.ALARM, Instant.now()).copy(name = "Morning")
        launch(newLoopArchive().copy(routines = listOf(wake)))
        ui.waitUntil(5_000) { app.engine.archive.value.installed.isNotEmpty() }
        val row = app.engine.archive.value.installed.single()
        kotlinx.coroutines.runBlocking { app.engine.openNative(row.id) }
        ui.onNodeWithText("Wake up").assertIsDisplayed()
        ui.onNodeWithTag("snooze").assertIsDisplayed()
        ui.onNodeWithTag("im-up").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.events.isNotEmpty() }
        assertEquals(EventKind.COMPLETED, app.engine.archive.value.events.single().kind)
        assertNull(app.engine.archive.value.pending)
        ui.onNodeWithTag("tab-History").performClick()
        ui.onNodeWithText("Mission completed").assertIsDisplayed()
    }

    @Test
    fun historyCorrectionIsMarkedManual() {
        val loop = newRoutine(RoutineKind.LOOP, Instant.now()).copy(name = "Stretch")
        val event = newLoopEvent(loop, EventKind.SNOOZED, Instant.now().minusSeconds(600), "")
        launch(newLoopArchive().copy(routines = listOf(loop), events = listOf(event)))
        ui.onNodeWithTag("tab-History").performClick()
        ui.onNodeWithText("Last 7 days").assertIsDisplayed()
        ui.onNodeWithTag("event-Stretch").performClick()
        ui.onNode(hasSetTextAction() and hasText("Note")).performTextInput("Did it late")
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.events.single().kind == EventKind.MANUAL }
        assertEquals("Did it late", app.engine.archive.value.events.single().note)
        ui.onNodeWithText("Manual check-in").assertIsDisplayed()
    }

    @Test
    fun settingsShowsDeliveryAndTheSharedSections() {
        launch()
        ui.onNodeWithTag("tab-Settings").performClick()
        ui.onNodeWithText("Ring a test alarm in 10 seconds").assertIsDisplayed()
        ui.onNodeWithText("New alarm defaults").performScrollTo().performClick()
        ui.onNodeWithTag("Snooze: 5 minutes-plus").performClick()
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.alarmDefaults?.snoozeMinutes == 6 }
        ui.onNodeWithText("Open-source licenses").performScrollTo().assertIsDisplayed()
    }
}
