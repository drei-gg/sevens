package gg.seven.alarm

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.alarm.core.*
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
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

    @Test
    fun routineTemplateHasExplicitReviewAndSavesAReusableDormantCopy() {
        val original = newRoutine(RoutineKind.ALARM, Instant.now()).copy(name = "Template review")
        launch(newLoopArchive().copy(routines = listOf(original)))
        if (
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("featureOrientation") == "landscape"
        ) {
            scenario!!.onActivity {
                it.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            ui.waitUntil(5_000) {
                app.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
        }

        ui.onNodeWithTag("routines-ALARM")
            .performScrollToNode(hasTestTag("routine-options-Template review"))
        ui.onNodeWithTag("routine-options-Template review").performScrollTo().performClick()
        ui.onNodeWithText("Save routine template").performClick()
        ui.onNodeWithText("Save a routine template?").assertIsDisplayed()
        capture("alarm-template-consent")
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(original), app.engine.archive.value.routines)
        val file = java.io.File(app.cacheDir, "template-test-${java.util.UUID.randomUUID()}.json")
        try {
            scenario!!.onActivity { activity ->
                val model = androidx.lifecycle.ViewModelProvider(activity)[AlarmModel::class.java]
                model.prepareTemplate(original) {
                    model.exportTemplate(android.net.Uri.fromFile(file))
                }
            }
            ui.waitUntil(10_000) { file.exists() && file.length() > 0 }
            val template = decodeArchive(file.readBytes())
            assertEquals(1, template.routines.size)
            assertFalse(template.routines.single().enabled)
            assertNotEquals(original.id, template.routines.single().id)
            assertTrue(template.events.isEmpty())
            assertTrue(template.installed.isEmpty())
            assertEquals(listOf(original), app.engine.archive.value.routines)
            val merged = backupMerge(template, app.engine.archive.value)
            assertEquals(2, merged.routines.size)
            assertEquals(merged, backupMerge(template, merged))
        } finally {
            file.delete()
        }
    }

    @Test
    fun importedSoundPreviewDraftRotationPickerCancelAndFallback() {
        val sounds = SoundFiles(app)
        val sound = app.assets.open("alarm-chime.wav").use { sounds.import(it, "Morning clip.wav") }
        val original =
            newRoutine(RoutineKind.ALARM, Instant.now())
                .copy(
                    name = "Imported sound",
                    enabled = false,
                    tone = AlarmTone.PULSE,
                    importedSound = sound,
                )
        try {
            launch(newLoopArchive().copy(routines = listOf(original)))
            val landscape =
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("featureOrientation") == "landscape"
            scenario!!.onActivity {
                it.requestedOrientation =
                    if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            ui.waitUntil(5_000) {
                app.resources.configuration.orientation ==
                    if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            ui.onNodeWithTag("routines-ALARM")
                .performScrollToNode(hasTestTag("edit-Imported sound"))
            ui.onNodeWithTag("edit-Imported sound").performClick()
            ui.onNodeWithTag("imported-sound")
                .performScrollTo()
                .assertTextEquals("Morning clip.wav")
            ui.onNodeWithTag("preview-tone").performScrollTo().performClick()
            ui.onNodeWithTag("preview-tone").assertTextContains("Stop preview").performClick()
            ui.onNodeWithTag("preview-tone").assertTextContains("Preview imported sound")
            ui.waitUntil(5_000) {
                android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .filter { it.hasWindowFocus() }
                    .all {
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
                    }
            }
            ui.onNodeWithTag("import-sound").performScrollTo()
            android.os.SystemClock.sleep(500)
            capture("alarm-imported-sound")
            // Audit the focused editor only; the obscured activity is not this viewport.
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
            scenario!!.recreate()
            ui.onNodeWithTag("imported-sound")
                .performScrollTo()
                .assertTextEquals("Morning clip.wav")
            assertTrue(sounds.available(sound))
            ui.onNodeWithTag("import-sound").performScrollTo().performClick()
            val automation =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.waitUntil(5_000) {
                automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") ==
                    true
            }
            automation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            ui.onNodeWithTag("imported-sound")
                .performScrollTo()
                .assertTextEquals("Morning clip.wav")
            sounds.file(sound).delete()
            scenario!!.recreate()
            ui.onNodeWithText("This audio file is missing.", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            capture("alarm-missing-sound")
            ui.onNodeWithText("Use built-in tone").performScrollTo().performClick()
            ui.onNodeWithTag("editor-save").performClick()
            ui.waitUntil(5_000) { app.engine.archive.value.routines.single().importedSound == null }
            assertEquals(AlarmTone.PULSE, app.engine.archive.value.routines.single().tone)
            assertFalse(app.engine.archive.value.routines.single().enabled)
        } finally {
            sounds.file(sound).delete()
        }
    }

    @Test
    fun importedSoundProviderResultIsValidatedBeforeDraftAndSave() {
        val original =
            newRoutine(RoutineKind.ALARM, Instant.now())
                .copy(name = "Provider import", enabled = false, tone = AlarmTone.PULSE)
        val resolver = app.contentResolver
        val uri =
            resolver.insert(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(
                        android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
                        "Owned morning clip.wav",
                    )
                    put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
                },
            )!!
        var owned: ImportedSound? = null
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun resultMonitor() =
            instrumentation.addMonitor(
                android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    addDataType("*/*")
                },
                android.app.Instrumentation.ActivityResult(
                    android.app.Activity.RESULT_OK,
                    android.content.Intent().setData(uri),
                ),
                true,
            )
        var sourceDeleted = false
        try {
            app.assets.open("alarm-chime.wav").use { input ->
                resolver.openOutputStream(uri)!!.use { input.copyTo(it) }
            }
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            launch(newLoopArchive().copy(routines = listOf(original)))
            ui.onNodeWithTag("routines-ALARM")
                .performScrollToNode(hasTestTag("edit-Provider import"))
            ui.onNodeWithTag("edit-Provider import").performClick()
            val monitor = resultMonitor()
            try {
                ui.onNodeWithTag("import-sound").performScrollTo().performClick()
                ui.waitUntil(10_000) {
                    ui.onAllNodesWithTag("imported-sound").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(1, monitor.hits)
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            ui.onNodeWithTag("imported-sound")
                .performScrollTo()
                .assertTextEquals("Owned morning clip.wav")
            assertEquals(null, app.engine.archive.value.routines.single().importedSound)
            scenario!!.onActivity {
                owned =
                    androidx.lifecycle
                        .ViewModelProvider(it)[AlarmModel::class.java]
                        .draft
                        .value!!
                        .importedSound
            }
            scenario!!.recreate()
            assertTrue(SoundFiles(app).available(owned!!))
            // Malformed provider data must not replace the valid draft or its owned audio.
            resolver.openOutputStream(uri, "wt")!!.use { it.write("not audio".toByteArray()) }
            val invalid = resultMonitor()
            try {
                ui.onNodeWithTag("import-sound").performScrollTo().performClick()
                ui.waitUntil(10_000) {
                    ui.onAllNodesWithText("Choose a readable PCM WAV file.", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            } finally {
                instrumentation.removeMonitor(invalid)
            }
            ui.onNodeWithText("OK").performClick()
            ui.onNodeWithTag("imported-sound")
                .performScrollTo()
                .assertTextEquals("Owned morning clip.wav")
            resolver.delete(uri, null, null)
            sourceDeleted = true
            ui.onNodeWithTag("editor-save").performClick()
            ui.waitUntil(5_000) {
                app.engine.archive.value.routines.single().importedSound == owned
            }
            Tones.player(app, AlarmTone.PULSE, false, owned).release()
            assertFalse(app.engine.archive.value.routines.single().enabled)
        } finally {
            if (!sourceDeleted) resolver.delete(uri, null, null)
            owned?.let { SoundFiles(app).file(it).delete() }
        }
    }

    private fun launch(seed: LoopArchive = newLoopArchive(), trial: Boolean = true) {
        app.getSharedPreferences("seven-launch", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("beforeYouBuy", true)
            .commit()
        val start =
            if (trial) System.currentTimeMillis() else System.currentTimeMillis() - 8 * 86_400_000L
        app.getSharedPreferences("seven-trial", Context.MODE_PRIVATE)
            .edit()
            .putLong("start", start)
            .putLong("lastSeen", System.currentTimeMillis())
            .commit()
        driver = PreviewAlarmDriver()
        app.engine = AlarmEngine(ArchiveRepository(null, seed), driver, isPreview = true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.waitUntil(10_000) { app.engine.ready.value }
    }

    @After
    fun close() {
        scenario?.close()
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
    fun m3ReviewNap() {
        launch()
        ui.onNodeWithTag("routines-ALARM").performScrollToNode(hasTestTag("custom-nap"))
        ui.onNodeWithTag("custom-nap").performClick()
        ui.onNodeWithTag("start-custom-nap").assertExists()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        capture("m3-alarm-nap")
        // The numeric form opens a second modal window. The default Compose adapter also checks
        // the obscured activity/rail, whose bounds shrink under the IME. Audit the focused window
        // explicitly below; retain every ATF check rather than suppressing a class of results.
        ui.disableAccessibilityChecks()
        ui.onNodeWithContentDescription("Edit Duration in minutes").performScrollTo().performClick()
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String =
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                    .bufferedReader()
                    .readText()
                    .trim()
            }
        val showKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            ui.onNodeWithTag("integer-setting").performClick().performTextReplacement("721")
            ui.waitUntil(5_000) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.hasWindowFocus() &&
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
            }
            ui.onNodeWithText("Set").assertIsNotEnabled()
            capture("m3-alarm-keyboard")
            ui.runOnIdle {
                val focused =
                    android.view.inspector.WindowInspector.getGlobalWindowViews().filter {
                        it.hasWindowFocus()
                    }
                assertTrue("An active editor window must be audited", focused.isNotEmpty())
                val validator =
                    com.google.android.apps.common.testing.accessibility.framework.integrations
                        .espresso
                        .AccessibilityValidator()
                        .setRunChecksFromRootView(true)
                focused.forEach { validator.check(it) }
            }
            ui.onNodeWithTag("integer-setting").performTextReplacement("145")
            ui.onNodeWithText("Set").performClick()
            ui.onNodeWithTag("start-custom-nap").performScrollTo().performClick()
            ui.waitUntil(5_000) { app.engine.archive.value.routines.isNotEmpty() }
            assertEquals("145-minute nap", app.engine.archive.value.routines.single().name)
        } finally {
            shell(
                if (showKeyboard == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $showKeyboard"
            )
        }
    }

    @Test
    fun memoryPreviewHighlightsWhileInputIsDisabled() {
        val wake =
            newRoutine(RoutineKind.ALARM, Instant.now())
                .copy(mission = MissionKind.MEMORY, challengeLevel = 1)
        launch(newLoopArchive().copy(routines = listOf(wake)))
        ui.onNodeWithTag("try-mission").performScrollTo().performClick()
        val highlighted =
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                "Highlighted",
            )
        ui.waitUntil(5_000) { ui.onAllNodes(highlighted).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(highlighted).assertIsNotEnabled()
        capture("alarm-memory-highlight")
        assertTrue(app.engine.archive.value.events.isEmpty())
    }

    @Test
    fun numberOrderPreviewCompletesWithNativeTileButtons() {
        val wake =
            newRoutine(RoutineKind.ALARM, Instant.now())
                .copy(mission = MissionKind.NUMBER_ORDER, challengeLevel = 1)
        launch(newLoopArchive().copy(routines = listOf(wake)))
        ui.onNodeWithTag("try-mission").performScrollTo().performClick()
        for (number in 1..6) ui.onNodeWithText(number.toString(), substring = false).performClick()
        ui.onNodeWithText("Done.").assertExists()
        assertTrue(app.engine.archive.value.events.isEmpty())
    }

    @Test
    fun intervalLoopAcceptsDirectEntryAndPersistsTheSchedule() {
        val loop =
            newRoutine(RoutineKind.LOOP, Instant.now())
                .copy(name = "Stretch window", timing = Timing.WINDOW)
        launch(newLoopArchive().copy(routines = listOf(loop)))
        ui.onNodeWithTag("tab-Loops").performClick()
        ui.onNodeWithTag("routine-card-Stretch window").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Edit Interval in minutes").performScrollTo().performClick()
        ui.onNodeWithTag("integer-setting").performTextReplacement("360")
        ui.onNodeWithText("Set").performClick()
        capture("alarm-interval-window")
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.routines.single().intervalMinutes == 360 }
    }

    @Test
    fun visibleRoutineMenuDuplicatesWithoutLongPress() {
        val wake = newRoutine(RoutineKind.ALARM, Instant.now()).copy(name = "Morning run")
        launch(newLoopArchive().copy(routines = listOf(wake)))
        ui.onNodeWithTag("routine-options-Morning run").performScrollTo().performClick()
        capture("alarm-routine-actions")
        ui.onNodeWithText("Duplicate").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.routines.size == 2 }
        assertEquals(wake.slots, app.engine.archive.value.routines.last().slots)
    }

    @Test
    fun defaultsDraftSurvivesRecreationAndCanBeDiscarded() {
        launch()
        ui.onNodeWithTag("tab-Settings").performClick()
        ui.onNodeWithText("New alarm defaults").performScrollTo().performClick()
        ui.onNodeWithTag("Snooze: 5 minutes-plus").performClick()
        scenario!!.recreate()
        ui.onNodeWithTag("Snooze: 6 minutes-plus").assertExists()
        capture("alarm-defaults")
        ui.onNodeWithTag("editor-cancel").performClick()
        ui.onNodeWithText("Keep editing").performClick()
        ui.onNodeWithTag("editor-save").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.alarmDefaults?.snoozeMinutes == 6 }
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
        // The generic build is the public source, unlocked with no trial
        // (SourceBuildPurchaseSource); the personal build without its build-time unlock and the store build keep the trial.
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR != "generic" && !BuildConfig.PERSONAL_UNLOCK)
        launch(trial = false)
        ui.onNodeWithTag("nap-15").performClick()
        ui.onNodeWithText("Needs attention").assertIsDisplayed()
        ui.onNodeWithText(
                "Start the trial or unlock in Settings to create or edit schedules. Existing enabled alarms keep running."
            )
            .assertIsDisplayed()
        ui.onNodeWithText("OK").performClick()
        assertTrue(app.engine.archive.value.routines.isEmpty())
    }

    @Test
    fun theSourceBuildCreatesAlarmsWithoutATrial() {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR == "generic")
        // A trial window that would have ended long ago changes nothing in a build from source.
        launch(trial = false)
        ui.onNodeWithTag("nap-15").performClick()
        ui.waitUntil(5_000) { app.engine.archive.value.routines.isNotEmpty() }
        assertEquals("15-minute nap", app.engine.archive.value.routines.single().name)
    }

    @Test
    fun aReopenedMissionResumesWhereItWasLeft() {
        val wake =
            newRoutine(RoutineKind.ALARM, Instant.now())
                .copy(name = "Morning", mission = MissionKind.ARITHMETIC, mathCount = 3)
        launch(newLoopArchive().copy(routines = listOf(wake)))
        ui.waitUntil(5_000) { app.engine.archive.value.installed.isNotEmpty() }
        val row = app.engine.archive.value.installed.single()
        kotlinx.coroutines.runBlocking {
            app.engine.openNative(row.id)
            // One question answered before a call took the person away.
            app.engine.saveProgress(
                missionProgressStart(app.engine.archive.value.pending!!).copy(solved = 1)
            )
        }
        scenario?.recreate()
        ui.onNodeWithText("Question 2 of 3").assertIsDisplayed()
        // The app is closed and opened again: still the second question.
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.waitUntil(10_000) { app.engine.ready.value }
        ui.onNodeWithText("Question 2 of 3").assertIsDisplayed()
        assertTrue(app.engine.archive.value.events.isEmpty())
    }

    @Test
    fun theArithmeticPreviewCompletesWithoutRecording() {
        val wake =
            newRoutine(RoutineKind.ALARM, Instant.now())
                .copy(mission = MissionKind.ARITHMETIC, mathCount = 1)
        launch(newLoopArchive().copy(routines = listOf(wake)))
        ui.onNodeWithTag("try-mission").performScrollTo().performClick()
        ui.onNodeWithText("Mission preview. No alarm will ring.").assertIsDisplayed()
        ui.onNodeWithTag("mission-answer").performTextInput("1")
        ui.onNodeWithTag("check-answer").performClick()
        ui.onNodeWithText("Try that one again.").assertIsDisplayed()
        // The sum the screen shows is read from its accessibility label.
        val label =
            ui.onNode(hasContentDescription(" plus ", substring = true))
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
                .single()
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
