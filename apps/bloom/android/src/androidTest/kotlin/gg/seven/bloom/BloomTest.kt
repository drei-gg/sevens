package gg.seven.bloom

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
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
    fun backupPassphraseUsesAnExplicitVisibilityControl() {
        ui.waitUntil(15_000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        for (label in listOf("Done", "Look around first")) {
            if (ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
                ui.onNodeWithText(label).performClick()
        }
        ui.onNodeWithText("Settings", substring = false).performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String =
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                    .bufferedReader()
                    .readText()
                    .trim()
            }
        val keyboard = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            val field = ui.onNode(hasSetTextAction() and hasText("Backup passphrase"))
            field.performScrollTo().performClick().performTextInput("private backup review")
            ui.waitUntil(5_000) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.hasWindowFocus() &&
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
            }
            field.assert(
                SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.Password
                )
            )
            ui.onNodeWithContentDescription("Show backup passphrase")
                .performScrollTo()
                .performClick()
            // Password keyboards keep secure semantics even while the user reveals the text.
            // Check the rendered transformation rather than expecting those semantics to disappear.
            fun renderedText(): String {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                field.performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
                ) {
                    it(layouts)
                }
                return layouts.single().layoutInput.text.text
            }
            assertEquals("private backup review", renderedText())
            field.assertTextContains("private backup review")
            ui.onNodeWithContentDescription("Hide backup passphrase")
                .performScrollTo()
                .performClick()
            assertFalse(renderedText().contains("private backup review"))
            field.assert(
                SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.Password
                )
            )
            field.performScrollTo()
            val bounds = field.getUnclippedBoundsInRoot()
            val decor = ui.activity.window.decorView
            val ime =
                androidx.core.view.ViewCompat.getRootWindowInsets(decor)!!
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                    .bottom
            val visibleBottom = (decor.height - ime) / context.resources.displayMetrics.density
            assertTrue(
                "The complete passphrase field must fit above the IME",
                bounds.bottom.value <= visibleBottom + 1f,
            )
            screenshot("deep-coverage-bloom-backup-secret")
            field.performImeAction()
        } finally {
            shell(
                if (keyboard == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $keyboard"
            )
        }
    }

    @Test
    fun m3ReviewSymptoms() {
        ui.waitUntil(15_000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        for (label in listOf("Done", "Look around first")) {
            if (ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
                ui.onNodeWithText(label).performClick()
        }
        ui.onNodeWithText("Log today").performScrollTo().performClick()
        ui.onNodeWithText("Cramps", substring = false).performScrollTo()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        screenshot("deep-m3-bloom-symptoms")
        ui.onNodeWithContentDescription("Cramps None").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Cramps None").assertIsSelected()
        screenshot("deep-m3-bloom-symptom-choice")
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(10_000) {
            runBlocking {
                JSONObject(BloomRepository(context).read()).rows("days").any {
                    it.getString("day") == java.time.LocalDate.now().toString() &&
                        it.getJSONObject("symptoms").optInt("Cramps", -1) == 0
                }
            }
        }
        ui.onNodeWithText("Log today").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Clear Cramps").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Cramps None").assertIsNotSelected()
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(10_000) {
            runBlocking {
                JSONObject(BloomRepository(context).read())
                    .rows("days")
                    .first { it.getString("day") == java.time.LocalDate.now().toString() }
                    .getJSONObject("symptoms")
                    .has("Cramps")
                    .not()
            }
        }
    }

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
    fun weeklyPlanUsesCheckboxesAndSavesChosenWeekdays() {
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        for (label in listOf("Done", "Look around first")) if (
            ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        )
            ui.onNodeWithText(label).performClick()
        ui.onNodeWithText("Care").performClick()
        ui.onNodeWithText("Add pill plan").performScrollTo().performClick()
        ui.onNodeWithText("Plan name").performTextReplacement("Weekly checkbox fixture")
        ui.onNodeWithText("Schedule").performScrollTo().performClick()
        ui.onNodeWithText("Weekly").performClick()
        ui.onNodeWithText("Monday").performScrollTo().performClick()
        ui.onNodeWithText("Monday").assertIsOff()
        screenshot("deep-bloom-weekday-checkboxes")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Monday").performScrollTo().assertIsOff()
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(10000) {
            runBlocking {
                JSONObject(BloomRepository(context).read()).rows("plans").any {
                    it.getString("name") == "Weekly checkbox fixture" &&
                        it.getString("pattern") == "weekly" &&
                        !(0 until it.getJSONArray("weekdays").length())
                            .map { index -> it.getJSONArray("weekdays").getInt(index) }
                            .contains(2)
                }
            }
        }
    }

    @Test
    fun closingAnEditedDayKeepsOrDiscardsTheDraftExplicitly() {
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        for (label in listOf("Done", "Look around first")) if (
            ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        )
            ui.onNodeWithText(label).performClick()
        ui.onNodeWithText("Log today").performClick()
        ui.onNodeWithText("Notes").performScrollTo().performTextReplacement("Unsaved Bloom draft")
        ui.onNodeWithContentDescription("Close").performClick()
        ui.onNodeWithText("Keep editing").performClick()
        ui.onNodeWithText("Unsaved Bloom draft").assertExists()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithContentDescription("Close").performClick()
        ui.onNodeWithText("Discard", substring = false).performClick()
        ui.onNodeWithText("Log today").assertExists()
        val persisted = runBlocking { JSONObject(BloomRepository(context).read()) }
        assertTrue(persisted.rows("days").none { it.getString("notes") == "Unsaved Bloom draft" })
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
        ui.onNodeWithText("Add pill plan").performScrollTo().performClick()
        ui.onNodeWithText("Plan name").performTextReplacement("Android pill fixture")
        ui.onNodeWithText("Save").performClick()
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
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Calendar").performClick()
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

    @Test
    fun timedObservationSurvivesAndAppearsInCalendar() {
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        for (label in listOf("Done", "Look around first")) if (
            ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        )
            ui.onNodeWithText(label).performClick()
        ui.onNodeWithText("Add timed observation").performScrollTo().performClick()
        val label = "Focused ${UUID.randomUUID().toString().take(6)}"
        ui.onNodeWithText("Observation name").performScrollTo().performTextReplacement(label)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText(label).assertExists()
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Calendar").performClick()
        ui.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        screenshot("android-timed-calendar")
        ui.onNodeWithText("Patterns").performClick()
        screenshot("android-patterns")
    }

    @Test
    fun reminderDeliveryDoesNotRecordAnAction(): Unit = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation
            .executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            )
            .close()
        automation
            .executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
            .use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
        assertTrue(BloomReminders.exact(context))
        val repo = (context.applicationContext as BloomApplication).repository
        val now = java.time.Instant.now().epochSecond
        val appointment =
            newRecord("appointment")
                .put("title", "Reminder fixture")
                .put("at", now + 8)
                .put("remind_minutes", 0)
        repo.change(
            command("appointment", appointment, JSONObject(repo.read()).getLong("revision")),
            true,
        )
        BloomReminders.reconcile(context, now = now)
        val next =
            context.getSharedPreferences("bloom-reminders", Context.MODE_PRIVATE).getLong("next", 0)
        assertTrue(next in (now + 1)..(now + 8))
        val before = repo.read()
        kotlinx.coroutines.delay(11000)
        // The real AlarmManager receiver must deliver without an explicit engine invocation.
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val tag = "bloom.appointment.${appointment.getString("id")}"
        assertTrue(manager.activeNotifications.any { it.tag == tag })
        assertEquals(before, repo.read())
        BloomReminders.reconcile(context, deliver = true)
        assertEquals(1, manager.activeNotifications.count { it.tag == tag })
        repo.change(
            command(
                "delete_appointment",
                null,
                JSONObject(repo.read()).getLong("revision"),
                appointment.getString("id"),
            ),
            true,
        )
        BloomReminders.reconcile(context)
        assertFalse(manager.activeNotifications.any { it.tag == tag })
    }

    @Test
    fun presentationWithHistoryAndPrivateReport() {
        runBlocking {
            val repo = (context.applicationContext as BloomApplication).repository
            for (offset in listOf(86, 58, 30, 3, 2, 1)) {
                val date = java.time.LocalDate.now().minusDays(offset.toLong()).toString()
                val a = JSONObject(repo.read())
                val row =
                    a.rows("days").firstOrNull { it.getString("day") == date }
                        ?: newRecord("day").put("day", date)
                row.put("flow", if (offset == 1) "light" else "medium")
                    .put("period_start", offset !in listOf(2, 1))
                    .put("bbt", 36.4 + (offset % 4) * 0.1)
                    .put("symptoms", JSONObject().put("Cramps", 1).put("Calm", 2))
                repo.change(command("day", row, a.getLong("revision")), true)
            }
        }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("Log today").fetchSemanticsNodes().isNotEmpty()
        }
        for (label in listOf("Done", "Look around first")) if (
            ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        )
            ui.onNodeWithText(label).performClick()
        screenshot("android-today-history")
        ui.onNodeWithText("Calendar").performClick()
        screenshot("android-calendar-history")
        ui.onNodeWithText("Patterns").performClick()
        screenshot("android-patterns-history")
        ui.onNodeWithText("Preview report").performScrollTo().performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Preview ready").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Read report text").performScrollTo().performClick()
        ui.onNode(hasText("Cycle & Health Report", substring = true)).assertExists()
        ui.onNodeWithText("Today").performClick()
        val file = java.io.File(context.getExternalFilesDir(null), "report-pagination.pdf")
        file.delete()
        lateinit var model: BloomModel
        ui.runOnIdle {
            model = androidx.lifecycle.ViewModelProvider(ui.activity)[BloomModel::class.java]
            val text = (1..200).joinToString("\n") { "Observation $it: full report line" }
            // As if the picker returned this document for a prepared export.
            model.exports.prepare(PendingExport("report.pdf", reportBytes(text, "pdf")))
            model.picked(android.net.Uri.fromFile(file))
        }
        ui.waitUntil(15000) { !model.busy.value && file.exists() }
        assertNull(model.error.value)
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            .use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                    assertTrue(pdf.pageCount > 1)
                    pdf.openPage(pdf.pageCount - 1).use { page ->
                        val bitmap =
                            android.graphics.Bitmap.createBitmap(
                                page.width,
                                page.height,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                        page.render(
                            bitmap,
                            null,
                            null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        bitmap.recycle()
                    }
                }
            }
    }
}
