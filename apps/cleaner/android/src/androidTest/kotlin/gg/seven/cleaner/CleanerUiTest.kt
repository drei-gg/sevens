package gg.seven.cleaner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.cleaner.core.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The main flows on sample data (iOS CleanerTour's fixtures): no library, contacts or files
 * touched.
 */
@RunWith(AndroidJUnit4::class)
class CleanerUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private val model: CleanerModel
        get() = ViewModelProvider(ui.activity)[CleanerModel::class.java]

    @Before
    fun prepare() {
        if (ui.onAllNodesWithTag("seven-look-around").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("seven-look-around").performClick()
        ui.activity.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        // Seed the persisted fixture before refreshing; the previous entitlement can still be
        // Trial.
        model.store.access.startTrial()
        ui.runOnUiThread { model.store.refresh() }
        ui.waitUntil(5_000) { model.store.paidFeaturesAvailable }
        File(ui.activity.filesDir, "Cleaner/review.json").delete()
        ui.runOnUiThread { Fixtures.seed(model) }
        ui.waitForIdle()
    }

    private fun configureOrientation() {
        if (
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("featureOrientation") == "landscape"
        ) {
            ui.runOnUiThread {
                ui.activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            ui.waitUntil(5_000) {
                ui.activity.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            // Compose idle does not wait for the system's display-rotation surface animation.
            android.os.SystemClock.sleep(800)
        }
    }

    private fun decisions() = runBlocking { model.repository.read().decisions }

    @Test
    fun fileFiltersComposeWithoutChangingTheScanOrReviewDecisions() {
        configureOrientation()
        val base = model.media.value.first()
        val today =
            java.time.LocalDate.now()
                .atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
        val fixtures =
            listOf(
                base.copy(
                    id = "file-today",
                    name = "Today.pdf",
                    kind = MediaKind.FILE,
                    bytes = 20_000_000,
                    date = today,
                    fingerprint = "same",
                ),
                base.copy(
                    id = "file-past",
                    name = "Old.PDF",
                    kind = MediaKind.FILE,
                    bytes = 20_000_000,
                    date = today.atZone(java.time.ZoneId.systemDefault()).minusYears(4).toInstant(),
                    fingerprint = "same",
                ),
                base.copy(
                    id = "file-image",
                    name = "Photo.jpg",
                    kind = MediaKind.FILE,
                    bytes = 200_000,
                    date = today,
                    fingerprint = null,
                ),
                base.copy(
                    id = "file-unknown",
                    name = "Unknown.pdf",
                    kind = MediaKind.FILE,
                    bytes = null,
                    date = null,
                    fingerprint = null,
                ),
                base.copy(
                    id = "file-empty",
                    name = "LICENSE",
                    kind = MediaKind.FILE,
                    bytes = 0,
                    date = today,
                    fingerprint = null,
                ),
            )
        val scan = FolderScan(fixtures, matchingExact(fixtures), 0)
        ui.runOnUiThread { model.folderScan.value = scan }
        val before = decisions()
        if (
            ui.onAllNodesWithContentDescription("Navigation menu")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            ui.onNodeWithContentDescription("Navigation menu").performClick()
        ui.onNodeWithTag("nav-Files").performClick()
        fun show(tag: String) {
            ui.onNodeWithTag("files").performScrollToNode(hasTestTag(tag))
            ui.onNodeWithTag(tag).performScrollTo()
        }
        fun open() {
            show("files-filter-open")
            ui.onNodeWithTag("files-filter-open").performClick()
        }
        fun choose(tag: String, value: String) {
            ui.onNodeWithTag(tag).performScrollTo().performClick()
            ui.onNode(hasText(value) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        }
        fun count(value: Int) {
            show("files-visible-count")
            ui.onNodeWithTag("files-visible-count").assertTextEquals("$value of 5 files")
        }
        open()
        choose("files-extension", ".pdf")
        choose("files-size", "At least 10 MB")
        choose("cleaner-date-mode", "On a date")
        capture("cleaner-file-filters")
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
        ui.onNodeWithTag("editor-save").performClick()
        count(1)
        ui.activityRule.scenario.recreate()
        count(1)
        open()
        choose("files-size", "Size unknown")
        ui.activityRule.scenario.recreate()
        ui.onNode(hasText("Size unknown") and hasAnyAncestor(hasTestTag("files-size")))
            .assertExists()
        ui.onNodeWithTag("editor-cancel").performClick()
        count(1)
        show("files-clear")
        android.os.SystemClock.sleep(800)
        capture("cleaner-file-results")
        open()
        ui.onNodeWithTag("files-clear-draft").performScrollTo().performClick()
        choose("files-size", "Size unknown")
        ui.onNodeWithTag("editor-save").performClick()
        count(1)
        ui.onNodeWithTag("files").performScrollToNode(hasTestTag("file-file-unknown"))
        ui.onNodeWithTag("file-file-unknown").assertExists()
        open()
        choose("files-size", "Any size")
        choose("files-extension", "No extension")
        ui.onNodeWithTag("editor-save").performClick()
        count(1)
        ui.onNodeWithTag("files").performScrollToNode(hasTestTag("file-file-empty"))
        ui.onNodeWithTag("file-file-empty").assertExists()
        show("files-clear")
        ui.onNodeWithTag("files-clear").performClick()
        count(5)
        open()
        ui.onNodeWithTag("files-duplicates").performScrollTo().performClick()
        ui.onNodeWithTag("editor-save").performClick()
        count(2)
        show("files-search")
        ui.onNodeWithTag("files-search").performTextReplacement("old")
        ui.onNodeWithTag("files-search").performImeAction()
        count(1)
        show("files-clear")
        ui.onNodeWithTag("files-clear").performClick()
        count(1)
        ui.activityRule.scenario.recreate()
        count(1)
        assertEquals(scan, model.folderScan.value)
        assertEquals(before, decisions())
    }

    @Test
    fun dateFiltersApplyCancelClearAndSurviveRecreationWithoutChangingDecisions() {
        configureOrientation()
        val today = java.time.LocalDate.now()
        fun stamp(day: java.time.LocalDate) =
            day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        val photo = model.media.value.first { it.kind == MediaKind.PHOTO }
        val video = model.media.value.first { it.kind == MediaKind.VIDEO }
        ui.runOnUiThread {
            model.media.value =
                listOf(
                    photo.copy(id = "date-today", name = "Today fixture", date = stamp(today)),
                    photo.copy(
                        id = "date-past",
                        name = "Past anniversary",
                        date = stamp(today.minusYears(4)),
                    ),
                    photo.copy(
                        id = "date-month",
                        name = "Other month",
                        date =
                            stamp(
                                today
                                    .withMonth(if (today.monthValue == 1) 2 else 1)
                                    .withDayOfMonth(1)
                            ),
                    ),
                    photo.copy(id = "date-unknown", name = "Unknown date", date = null),
                    video,
                )
            model.selected.value = setOf(video.id)
        }
        val before = decisions()
        if (
            ui.onAllNodesWithContentDescription("Navigation menu")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            ui.onNodeWithContentDescription("Navigation menu").performClick()
        ui.onNodeWithTag("nav-Photos").performClick()
        fun open() {
            ui.onNodeWithContentDescription("Sort and filter").performClick()
            ui.onNodeWithTag("cleaner-date-open").performScrollTo().performClick()
        }
        fun choose(label: String) {
            ui.onNodeWithTag("cleaner-date-mode").performClick()
            ui.onNode(hasText(label) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        }
        fun selectedIds(): Set<String> {
            ui.runOnUiThread { model.selected.value = setOf(video.id) }
            ui.onNodeWithContentDescription("Batch actions").performClick()
            ui.onNodeWithText("Select visible").performClick()
            return model.selected.value
        }
        open()
        choose("On this day")
        capture("cleaner-date-editor")
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
        ui.onNodeWithTag("editor-save").performClick()
        assertEquals(setOf(video.id), model.selected.value)
        assertEquals(setOf(video.id, "date-past"), selectedIds())
        ui.activityRule.scenario.recreate()
        assertEquals(setOf(video.id, "date-past"), selectedIds())
        open()
        choose("In a month")
        ui.activityRule.scenario.recreate()
        ui.onNode(hasText("In a month") and hasAnyAncestor(hasTestTag("cleaner-date-mode")))
            .assertExists()
        ui.onNodeWithTag("editor-cancel").performClick()
        assertEquals(setOf(video.id, "date-past"), selectedIds())
        open()
        choose("In a month")
        ui.onNodeWithTag("editor-save").performClick()
        assertEquals(setOf(video.id, "date-today"), selectedIds())
        open()
        choose("In a year")
        ui.onNodeWithTag("editor-save").performClick()
        assertEquals(setOf(video.id, "date-today", "date-month"), selectedIds())
        ui.onNodeWithTag("library-PHOTO").performScrollToNode(hasTestTag("cleaner-date-active"))
        ui.onNodeWithTag("cleaner-date-active").performScrollTo().assertIsDisplayed()
        capture("cleaner-date-results")
        ui.onNodeWithTag("cleaner-date-active").performClick()
        ui.onNodeWithTag("cleaner-date-active").assertDoesNotExist()
        assertEquals(
            setOf(video.id, "date-today", "date-past", "date-month", "date-unknown"),
            selectedIds(),
        )
        open()
        choose("On a date")
        ui.onNodeWithTag("Reference date-date").performScrollTo().performClick()
        val firstDay =
            today
                .withDayOfMonth(1)
                .format(
                    java.time.format.DateTimeFormatter.ofLocalizedDate(
                        java.time.format.FormatStyle.FULL
                    )
                )
        if (
            ui.onAllNodesWithContentDescription("Switch to calendar input mode")
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            ui.onNodeWithContentDescription("Switch to calendar input mode")
                .performScrollTo()
                .performClick()
            ui.onNodeWithText(firstDay).performScrollTo()
        }
        ui.onNodeWithText(firstDay).performClick().assertIsSelected()
        android.os.SystemClock.sleep(1_600)
        capture("cleaner-date-calendar")
        ui.onNodeWithText("OK").performClick()
        ui.onNodeWithTag("editor-save").performClick()
        assertEquals(
            setOf(video.id) + if (today.dayOfMonth == 1) setOf("date-today") else emptySet(),
            selectedIds(),
        )
        // Exercise Material's real text-input mode, including invalid input and the soft IME.
        open()
        ui.onNodeWithTag("Reference date-date").performScrollTo().performClick()
        if (
            ui.onAllNodesWithContentDescription("Switch to text input mode")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            ui.onNodeWithContentDescription("Switch to text input mode").performClick()
        val dateInput =
            ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("seven-date-picker")))
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) =
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                    .bufferedReader()
                    .readText()
                    .trim()
            }
        val keyboardSetting = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            if (ui.activity.resources.configuration.screenHeightDp < 500)
                dateInput.performScrollTo()
            dateInput.performClick().performTextReplacement("02302026")
            // Material 1.5 uses SMART date resolution: it visibly normalizes Feb 30 to Feb 28.
            // Retain the native behavior; an out-of-range month must produce a validation error.
            dateInput.assertTextContains("02/28/2026")
            dateInput.performTextReplacement("13202026")
            ui.onNodeWithText("OK").assertIsNotEnabled()
            val second = today.withDayOfMonth(2)
            dateInput.performTextReplacement(
                second.format(java.time.format.DateTimeFormatter.ofPattern("MMddyyyy"))
            )
            ui.onNodeWithText("OK").assertIsEnabled()
            ui.waitUntil(5_000) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.hasWindowFocus() &&
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
            }
            if (ui.activity.resources.configuration.screenHeightDp < 500)
                dateInput.performScrollTo()
            ui.waitUntil(5_000) {
                val window =
                    android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {
                        it.hasWindowFocus()
                    }
                val ime =
                    window?.let {
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)
                            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                            ?.bottom
                    }
                window != null &&
                    ime != null &&
                    dateInput.fetchSemanticsNode().boundsInRoot.bottom <= window.height - ime
            }
            android.os.SystemClock.sleep(1_600)
            capture("cleaner-date-input-keyboard")
            ui.onNodeWithText("OK").performClick()
            ui.onNodeWithTag("Reference date-date")
                .assertContentDescriptionContains(
                    "Reference date, " +
                        gg.seven.kit.SevenFormat.date(
                            second.atStartOfDay(java.time.ZoneId.systemDefault())
                        )
                )
        } finally {
            if (keyboardSetting == "null")
                shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $keyboardSetting")
        }
        ui.onNodeWithTag("editor-cancel").performClick()
        assertEquals(
            setOf(video.id) + if (today.dayOfMonth == 1) setOf("date-today") else emptySet(),
            selectedIds(),
        )
        assertEquals(before, decisions())
        // Native time-zone conversion around midnight must precede the shared civil-date rule.
        val boundary = java.time.Instant.parse("2026-01-01T00:30:00Z")
        assertTrue(
            matchesDate(
                boundary,
                MediaDateFilter.DAY,
                java.time.LocalDate.of(2025, 12, 31),
                java.time.ZoneId.of("America/Los_Angeles"),
            )
        )
        assertTrue(
            matchesDate(
                boundary,
                MediaDateFilter.DAY,
                java.time.LocalDate.of(2026, 1, 1),
                java.time.ZoneId.of("Asia/Manila"),
            )
        )
    }

    @Test
    fun savedComparisonProgressUsesNativeControlsAndClearKeepsDecisions() {
        configureOrientation()
        val first =
            model.media.value
                .first()
                .copy(revision = "checkpoint-1", fingerprint = "hash", bytes = 42)
        val before = runBlocking {
            model.measurements.clear()
            model.repository.decide(
                mapOf(first.id to ReviewDecision.KEEP),
                mapOf(first.id to first.revision),
            )
            model.measurements.checkpoint(first)
            File(ui.activity.filesDir, "Cleaner/review.json").readBytes()
        }
        ui.runOnUiThread {
            model.savedMeasurements.value = 1
            model.groups.value = emptyList()
            model.busy.value = true
            model.canCancel.value = true
            model.progress.value = "Checked 1 of 128 · 1 already measured"
            model.comparisonFraction.value = 1f / 128
        }
        ui.onNodeWithTag("comparison-progress").assertIsDisplayed()
        ui.onNodeWithText("Cancel").assertIsEnabled()
        capture("comparison-progress")
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
        ui.runOnUiThread {
            model.busy.value = false
            model.canCancel.value = false
            model.comparisonFraction.value = null
        }
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithText("Saved measurements: 1").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("clear-scan-progress").performScrollTo()
        capture("saved-scan-progress")
        ui.onNodeWithTag("clear-scan-progress").performClick()
        ui.waitUntil(5_000) { !model.busy.value && model.savedMeasurements.value == 0 }
        ui.onNodeWithText("Saved measurements: 0").performScrollTo().assertIsDisplayed()
        assertArrayEquals(before, File(ui.activity.filesDir, "Cleaner/review.json").readBytes())
        assertEquals(0, runBlocking { model.measurements.count() })
    }

    @Test
    fun qualityPreviewUsesOriginalAndFailedRetryKeepsPreviousCandidate() {
        configureOrientation()

        val directory =
            File(ui.activity.cacheDir, "quality-test-${java.util.UUID.randomUUID()}").apply {
                mkdirs()
            }
        val source = File(directory, "original.png")
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                1600,
                1200,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        bitmap.setHasAlpha(false)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint =
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 40f
            }
        canvas.drawText("Keep the original", 20f, 180f, paint)
        source.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val originalBytes = source.readBytes()
        val first = runBlocking { ImageCompression.makeCandidate(ui.activity, source, directory) }
        try {
            ui.runOnUiThread { model.candidate.value = first }
            ui.onNodeWithTag("compression-quality").performScrollTo().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetProgress
            ) {
                it(100f)
            }
            ui.onNodeWithTag("compression-save").assertIsNotEnabled()
            ui.onNodeWithTag("compression-update").performScrollTo().assertIsEnabled()
            capture("compression-before-update")
            ui.onNodeWithTag("compression-update").performClick()
            try {
                ui.waitUntil(15_000) {
                    model.candidate.value?.output != first.output && !model.busy.value
                }
            } catch (failure: Throwable) {
                capture("compression-update-failed")
                throw AssertionError(
                    "Update did not publish: busy=${model.busy.value}; error=${model.error.value}",
                    failure,
                )
            }
            val next = requireNotNull(model.candidate.value)
            assertEquals(1.0, next.quality, 0.0)
            assertEquals(source, next.source)
            org.junit.Assert.assertArrayEquals(originalBytes, source.readBytes())
            assertTrue(!first.output.exists())
            ui.onNodeWithTag("compression-update").assertIsNotEnabled()
            ui.onNodeWithText("Quality: 100%").performScrollTo().assertIsDisplayed()
            capture("compression-quality")
            ui.onNodeWithTag("compression-size").performScrollTo().performClick()
            ui.onNodeWithText("800 px max").performScrollTo().assertIsDisplayed().performClick()
            ui.onNodeWithTag("compression-save").assertIsNotEnabled()
            ui.onNodeWithTag("compression-update")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            try {
                ui.waitUntil(15_000) {
                    model.candidate.value?.size == ImageSizeProfile.SMALL && !model.busy.value
                }
            } catch (failure: Throwable) {
                capture("compression-resize-failed")
                throw AssertionError(
                    "Resize did not publish: busy=${model.busy.value}; size=${model.candidate.value?.size}; error=${model.error.value}",
                    failure,
                )
            }
            val resized = requireNotNull(model.candidate.value)
            assertEquals(800, resized.assessment.output.width)
            assertEquals(600, resized.assessment.output.height)
            assertEquals(1600, resized.assessment.source.width)
            assertArrayEquals(originalBytes, source.readBytes())
            ui.onNodeWithTag("compression-size").performScrollTo()
            capture("compression-resize")
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
            ui.runOnUiThread { model.updateCandidateQuality(0.8) }
            ui.waitUntil(10_000) { !model.busy.value && model.error.value != null }
            assertEquals(resized, model.candidate.value)
            assertTrue(resized.output.exists())
            org.junit.Assert.assertArrayEquals(originalBytes, source.readBytes())
        } finally {
            ui.runOnUiThread {
                model.error.value = null
                model.discardCandidate()
            }
            directory.deleteRecursively()
        }
    }

    @Test
    fun compressionQueueRetriesOneCopyAndKeepsOriginalsThroughRecreation() {
        configureOrientation()
        val context = ui.activity
        val resolver = context.contentResolver
        val automation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.READ_MEDIA_IMAGES,
        )
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                1200,
                900,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        for (y in 0 until 900) {
            paint.color = android.graphics.Color.rgb(40 + y / 8, 100 + y / 10, 160)
            canvas.drawRect(0f, y.toFloat(), 1200f, y + 1f, paint)
        }
        val bytes =
            java.io
                .ByteArrayOutputStream()
                .also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it) }
                .toByteArray()
        bitmap.recycle()
        val uri =
            requireNotNull(
                resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    android.content.ContentValues().apply {
                        put(
                            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                            "Queue original.jpg",
                        )
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            "Pictures/CleanerQueueTest/",
                        )
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )
            )
        var copyID: String? = null
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            val item = runBlocking {
                model.photos.inventory().items.single { it.id == uri.toString() }
            }
            val missing =
                item.copy(
                    id = "content://media/external/images/media/999999999",
                    name = "Unavailable photo.jpg",
                )
            val selected = setOf(item.id, missing.id)
            val stored = runBlocking {
                model.repository.decide(
                    mapOf(item.id to ReviewDecision.KEEP),
                    mapOf(item.id to item.revision),
                )
            }
            ui.runOnUiThread {
                model.media.value = listOf(item, missing)
                model.selected.value = selected
                model.state.value = stored
            }
            if (
                ui.onAllNodesWithContentDescription("Navigation menu")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            )
                ui.onNodeWithContentDescription("Navigation menu").performClick()
            ui.onNodeWithTag("nav-Photos").performClick()
            ui.onNodeWithContentDescription("Batch actions").performClick()
            ui.onNodeWithTag("compression-batch-open").performClick()
            ui.waitUntil(5_000) { model.compressionBatch.value != null && !model.busy.value }
            ui.onNodeWithTag("compression-batch-prepare").assertIsDisplayed()
            capture("compression-batch-ready")
            checkFocusedWindowAccessibility()
            ui.onNodeWithTag("compression-batch-prepare").performClick()
            ui.waitUntil(30_000) { model.candidate.value != null && !model.busy.value }
            val candidate = requireNotNull(model.candidate.value)
            assertTrue(
                candidate.assessment.issues.toString(),
                assessmentCanOfferCopy(candidate.assessment),
            )
            // Configuration changes retain the prepared source and queue. The latest inventory
            // must not silently replace the source revision used by the preview.
            ui.activityRule.scenario.recreate()
            ui.waitForIdle()
            assertEquals(item, model.candidateItem.value)
            ui.runOnUiThread {
                model.media.value = listOf(item.copy(revision = "changed inventory"), missing)
            }
            val ledger = File(context.filesDir, "Cleaner/review.json")
            val good = ledger.readBytes()
            ledger.writeText("broken ledger")
            ui.onNodeWithTag("compression-save").assertIsDisplayed().performClick()
            ui.waitUntil(30_000) {
                !model.busy.value && model.createdCopyID.value != null && model.error.value != null
            }
            copyID = model.createdCopyID.value
            assertEquals(0, model.compressionBatch.value!!.index)
            assertEquals(candidate, model.candidate.value)
            ui.onNodeWithText("Could not finish saving").assertIsDisplayed()
            capture("compression-batch-record-failed")
            ui.onNodeWithText("OK").performClick()
            ui.onNodeWithText("Retry verification").assertIsDisplayed()
            ui.onNodeWithTag("compression-quality").performScrollTo().assertIsNotEnabled()
            val count = runBlocking { model.photos.inventory().items.size }
            ledger.writeBytes(good)
            ui.onNodeWithTag("compression-save").performClick()
            ui.waitUntil(30_000) { !model.busy.value && model.compressionBatch.value?.index == 1 }
            assertEquals(count, runBlocking { model.photos.inventory().items.size })
            val reopened = runBlocking { ReviewStore(ledger).read() }
            assertEquals(stored.decisions, reopened.decisions)
            assertEquals(stored.history.size + 1, reopened.history.size)
            assertTrue(reopened.processedSources.contains(compressionSourceKey(item)))
            assertTrue(reopened.processedSources.contains("output:$copyID"))
            assertTrue(!candidate.output.parentFile!!.exists())
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            ui.onNodeWithTag("compression-batch-prepare").performClick()
            ui.waitUntil(15_000) { !model.busy.value && model.error.value != null }
            ui.onNodeWithText("Retry preparation").assertIsDisplayed()
            capture("compression-batch-unavailable")
            ui.onNodeWithTag("compression-skip").performClick()
            ui.onNodeWithTag("compression-batch-summary")
                .assertTextEquals("1 saved · 1 skipped · 0 unverified")
            ui.onNodeWithTag("compression-batch-done").assertIsDisplayed()
            capture("compression-batch-complete")
            checkFocusedWindowAccessibility()
            ui.onNodeWithTag("compression-batch-done").performClick()
            assertEquals(selected, model.selected.value)
            // Re-entering the known source is refused as a whole selection.
            ui.runOnUiThread {
                model.media.value = listOf(item, missing)
                model.startCompressionBatch(selected)
            }
            ui.waitUntil(5_000) { !model.busy.value && model.error.value != null }
            assertEquals(null, model.compressionBatch.value)
        } finally {
            ui.runOnUiThread {
                model.error.value = null
                model.stopCompressionBatch()
            }
            copyID?.let { resolver.delete(android.net.Uri.parse(it), null, null) }
            resolver.delete(uri, null, null)
        }
    }

    private fun checkFocusedWindowAccessibility() {
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
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        // Compose idle excludes Android window/surface transitions.
        android.os.SystemClock.sleep(400)
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
    fun contactExportAppearsBesideItsAction() {
        if (
            ui.onAllNodesWithContentDescription("Navigation menu")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            ui.onNodeWithContentDescription("Navigation menu").performClick()
        ui.onNodeWithTag("nav-Contacts").performClick()
        ui.onNodeWithText("Export fields").performScrollTo().performClick()
        ui.waitUntil(10_000) { model.exportFile.value != null && !model.busy.value }
        ui.onNodeWithText("Share partial contact archive").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Export fields").assertIsDisplayed()
        assertTrue(model.exportFile.value!!.length() > 0)
        capture("coverage-cleaner-contact-export")
    }

    @Test
    fun m3ReviewFilters() {
        if (
            ui.onAllNodesWithContentDescription("Navigation menu")
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            ui.onNodeWithContentDescription("Navigation menu").performClick()
            ui.onNodeWithTag("nav-Contacts").assertIsDisplayed()
            capture("m3-cleaner-navigation")
        }
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithContentDescription("Sort and filter").performClick()
        ui.onNode(hasText("Screenshots") and hasAnyAncestor(isPopup())).performClick()
        ui.onNodeWithTag("library-PHOTO").performScrollToNode(hasSetTextAction())
        ui.onNodeWithContentDescription("Clear collection filter")
            .performScrollTo()
            .assertIsDisplayed()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        capture("m3-cleaner-filters")
        // Audit the fully visible filter state above. Later lazy-grid scroll positions expose
        // clipped edge fragments to ATF (5dp/40dp targets); they remain reachable by scrolling.
        // Keep the behavioral assertions below, without claiming an ATF audit of those fragments.
        ui.disableAccessibilityChecks()
        ui.onNodeWithContentDescription("Clear collection filter").performClick()
        ui.onNodeWithTag("library-PHOTO").performScrollToNode(hasText("Browse your library"))
        ui.onNodeWithText("Browse your library").assertIsDisplayed()
        ui.runOnUiThread { model.selected.value = setOf("tour-video") }
        ui.onNodeWithContentDescription("Batch actions").performClick()
        ui.onNodeWithText("Select visible").performClick()
        assertTrue(
            "Other media selections survive Select visible",
            "tour-video" in model.selected.value,
        )
        ui.onNodeWithContentDescription("Batch actions").performClick()
        ui.onNodeWithText("Clear selection in this library").performClick()
        assertEquals(setOf("tour-video"), model.selected.value)
    }

    @Test
    fun batchActionsStayWithTheCurrentMediaKind() {
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithContentDescription("Select Coast at golden hour copy.heic")
            .performScrollTo()
            .performClick()
        ui.onNodeWithTag("nav-Videos").performClick()
        ui.onNodeWithText("1 selected").assertDoesNotExist()
        ui.onNodeWithContentDescription("Select The coast.mov").performScrollTo().performClick()
        ui.onNodeWithText("1 selected").assertIsDisplayed()
        capture("cleaner-video-selection")
        ui.onNodeWithText("Keep", substring = false).performClick()
        ui.waitUntil(5_000) { decisions()["tour-video"] == ReviewDecision.KEEP }
        assertTrue(
            decisions()["tour-b"] == null || decisions()["tour-b"] == ReviewDecision.UNDECIDED
        )
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun photoDetailShowsTheCommittedReviewChoice() {
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithTag("library-PHOTO").performScrollToNode(hasText("Coffee receipt.png"))
        ui.onNodeWithText("Coffee receipt.png").performClick()
        ui.onNodeWithTag("choice-Later").performScrollTo().performClick()
        ui.waitUntil(5_000) { decisions()["tour-receipt"] == ReviewDecision.LATER }
        ui.onNodeWithTag("choice-Later").assertIsSelected()
        capture("cleaner-review-choice")
        ui.onNodeWithTag("choice-Keep").performClick()
        ui.waitUntil(5_000) { decisions()["tour-receipt"] == ReviewDecision.KEEP }
        ui.onNodeWithTag("choice-Keep").assertIsSelected()
    }

    @Test
    fun queueingAndUndoingAreStoredBeforeTheyShow() {
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithContentDescription("Select Coast at golden hour copy.heic")
            .performScrollTo()
            .performClick()
        ui.onNodeWithText("1 selected").assertExists()
        ui.onAllNodesWithText("Queue removal")[0].assertIsDisplayed().performClick()
        ui.waitUntil(5_000) { decisions()["tour-b"] == ReviewDecision.REMOVE }
        assertEquals("1", model.state.value.revisions["tour-b"])
        ui.onNodeWithTag("nav-Overview").performClick()
        ui.onNodeWithTag("cleaner.pending").performScrollTo().performClick()
        ui.onNodeWithText("Coast at golden hour copy.heic").assertExists()
        ui.onNodeWithText("Undo").performClick()
        ui.waitUntil(5_000) { decisions()["tour-b"] == ReviewDecision.UNDECIDED }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(5_000) { model.state.value.decisions["tour-b"] == ReviewDecision.UNDECIDED }
    }

    @Test
    fun suggestedCopiesNeverIncludeTheFavorite() {
        ui.onNodeWithTag("cleaner.review-groups").performClick()
        ui.onNodeWithText("Select suggested exact copies").performClick()
        assertEquals(setOf("tour-b"), model.selected.value)
        ui.onNodeWithText("Queue 1 selected item").assertExists()
        ui.onNodeWithTag("groups").performScrollToNode(hasText("Identical files"))
        ui.onNodeWithTag("groups").performScrollToNode(hasText("Similar moments"))
    }

    @Test
    fun largeListsLargestFirstFromAnAdjustableMinimum() {
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithContentDescription("Sort and filter").performClick()
        ui.onNode(hasText("Large items") and hasAnyAncestor(isPopup())).performClick()
        // The fixture's photos are all under the 10 MB default; the unknown size never counts.
        ui.onNodeWithTag("library-PHOTO").performScrollToNode(hasText("Nothing in this collection"))
        ui.onNodeWithContentDescription("Sort and filter").performClick()
        ui.onNodeWithTag("cleaner-measure-open").performClick()
        ui.onNodeWithTag("media-large-minimum").performScrollTo().performClick()
        val five = "At least " + android.text.format.Formatter.formatFileSize(ui.activity, 5_000_000)
        ui.onNode(hasText(five) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        ui.onNodeWithText("Apply").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("library-PHOTO")
            .performScrollToNode(hasContentDescription("Clear filter: Large from", substring = true))
        val names =
            listOf("Coast at golden hour.heic", "Coast at golden hour copy.heic", "Weekend walk.heic")
        ui.onNodeWithTag("library-PHOTO").performScrollToNode(hasText(names.last()))
        val tops = names.map { ui.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Largest first: $tops", tops[0] <= tops[1] && tops[1] < tops[2])
        ui.onAllNodesWithText("Coffee receipt.png").assertCountEquals(0)
        ui.onAllNodesWithText("Another view.heic").assertCountEquals(0)
    }

    @Test
    fun collectionsFilterTheLibrary() {
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithText("Motion photos").performScrollTo().performClick()
        ui.onNodeWithText("Showing this collection").assertExists()
        ui.onNodeWithTag("library-PHOTO").performScrollToNode(hasText("Weekend walk.heic"))
        ui.onAllNodesWithText("Coffee receipt.png").assertCountEquals(0)
    }

    @Test
    fun contactCandidatesAreComparedNeverMerged() {
        ui.onNodeWithTag("nav-Contacts").performClick()
        ui.onNodeWithText("Compare contacts").performClick()
        ui.onNodeWithText("Shared phone number (including extension)", substring = true)
            .performClick()
        ui.onNodeWithText("Some source fields cannot be read. Keep the original records.")
            .performScrollTo()
            .assertExists()
        ui.onNodeWithText("No source contact is removed by this build.", substring = true)
            .performScrollTo()
            .assertExists()
        ui.onAllNodesWithText("Merge", substring = false).assertCountEquals(0)
    }

    /**
     * The generic build is the unlocked source build (no trial, nothing for sale), so trial-expiry
     * checks run on personal builds without the unlock and on store builds.
     */
    private val trialApplies: Boolean
        get() = !BuildConfig.PERSONAL_UNLOCK && !model.store.purchase.value.sourceBuild

    @Test
    fun anExpiredTrialKeepsDecisionsButNotNewScans() {
        org.junit.Assume.assumeTrue("The source build has no trial", trialApplies)
        ui.activity
            .getSharedPreferences("seven-trial", 0)
            .edit()
            .putLong("start", System.currentTimeMillis() - 8 * 86_400_000L)
            .commit()
        model.store.refresh()
        ui.waitForIdle()
        ui.onNodeWithTag("nav-Videos").performClick()
        ui.onNodeWithText("Find duplicates").performClick()
        ui.onNodeWithText("Start the free trial or unlock", substring = true).assertExists()
        ui.onNodeWithText("See the unlock").assertExists()
        ui.onNodeWithText("OK").performClick()
        // Reading what was decided stays free.
        ui.onNodeWithTag("nav-Overview").performClick()
        ui.onNodeWithText("Activity").performScrollTo().performClick()
        ui.onNodeWithText("Measured the accessible photo and video resources.").assertExists()
    }

    @Test
    fun filesShowExactCopiesAndUnmeasuredSizes() {
        ui.onNodeWithTag("nav-Files").performClick()
        ui.onNodeWithText("Duplicate groups").performClick()
        ui.onNodeWithText("Exact copies").performScrollTo().assertExists()
        ui.onNodeWithTag("files").performScrollToNode(hasText("Project archive copy.zip"))
        // Only in the largest-files chart, not in the list of copies.
        ui.onAllNodesWithText("Weekend footage.mov", substring = true).assertCountEquals(1)
        ui.onNodeWithTag("files").performScrollToNode(hasText("1 item has not been measured."))
    }
}

/** The iOS tour's sample library, people and folder. */
object Fixtures {
    fun seed(model: CleanerModel) {
        val date = Instant.ofEpochSecond(1_794_528_000)
        fun item(
            id: String,
            name: String,
            kind: MediaKind,
            bytes: Long?,
            w: Int,
            h: Int,
            favorite: Boolean = false,
            tags: List<String> = emptyList(),
            fingerprint: String? = null,
            duration: Double = 0.0,
        ) =
            CleanupItem(
                id,
                name,
                kind,
                "1",
                bytes,
                if (kind == MediaKind.FILE) null else date,
                w,
                h,
                duration,
                favorite,
                tags,
                fingerprint,
            )
        val media =
            listOf(
                item(
                    "tour-a",
                    "Coast at golden hour.heic",
                    MediaKind.PHOTO,
                    8_400_000,
                    4032,
                    3024,
                    favorite = true,
                    fingerprint = "same",
                ),
                item(
                    "tour-b",
                    "Coast at golden hour copy.heic",
                    MediaKind.PHOTO,
                    8_400_000,
                    4032,
                    3024,
                    fingerprint = "same",
                ),
                item(
                    "tour-c",
                    "Weekend walk.heic",
                    MediaKind.PHOTO,
                    6_200_000,
                    4032,
                    3024,
                    tags = listOf("Live Photos"),
                ),
                item(
                    "tour-receipt",
                    "Coffee receipt.png",
                    MediaKind.PHOTO,
                    2_900_000,
                    1179,
                    2556,
                    tags = listOf("Screenshots"),
                ),
                item("tour-d", "Another view.heic", MediaKind.PHOTO, null, 4032, 3024),
                item(
                    "tour-video",
                    "The coast.mov",
                    MediaKind.VIDEO,
                    482_000_000,
                    3840,
                    2160,
                    duration = 95.0,
                ),
                item(
                    "tour-recording",
                    "Screen recording.mov",
                    MediaKind.VIDEO,
                    176_000_000,
                    1179,
                    2556,
                    tags = listOf("Screen recordings"),
                    duration = 42.0,
                ),
            )
        val groups =
            matchingExact(media) +
                MatchGroup(
                    "similar",
                    MatchKind.SIMILAR,
                    listOf("tour-a", "tour-c"),
                    "Nearby capture times and similar image features. Review both moments.",
                )
        val state =
            newReviewState()
                .copy(
                    history =
                        listOf(
                            newHistoryEntry("Reviewed two matching photos. The favorite is kept."),
                            newHistoryEntry("Measured the accessible photo and video resources."),
                        )
                )
        runBlocking {
            model.repository.record("Measured the accessible photo and video resources.")
        }
        val files =
            listOf(
                item("file-a", "Weekend footage.mov", MediaKind.FILE, 1_450_000_000, 0, 0),
                item(
                    "file-b",
                    "Project archive.zip",
                    MediaKind.FILE,
                    680_000_000,
                    0,
                    0,
                    fingerprint = "zip",
                ),
                item(
                    "file-c",
                    "Project archive copy.zip",
                    MediaKind.FILE,
                    680_000_000,
                    0,
                    0,
                    fingerprint = "zip",
                ),
                item("file-d", "Reference.pdf", MediaKind.FILE, null, 0, 0),
            )
        fun field(key: String, value: String, label: String = "") =
            newContactField(key, value, label)
        val people =
            listOf(
                newContactRecord(
                    "contact-a",
                    "Alex Rivera",
                    "personal",
                    listOf(
                        field("givenName", "Alex"),
                        field("familyName", "Rivera"),
                        field("phone", "+63 917 555 0123", "Mobile"),
                        field("email", "alex@example.com", "Personal"),
                        field("birthday", "14 March"),
                        field("organization", "Weekend Studio"),
                    ),
                    listOf("+639175550123"),
                    emptyList(),
                ),
                newContactRecord(
                    "contact-b",
                    "Alex Rivera",
                    "personal",
                    listOf(
                        field("givenName", "Alex"),
                        field("familyName", "Rivera"),
                        field("phone", "0917 555 0123", "Mobile"),
                        field("email", "alex@studio.example", "Work"),
                    ),
                    listOf("+639175550123"),
                    emptyList(),
                ),
                newContactRecord(
                    "contact-c",
                    "Weekend Studio",
                    "personal",
                    listOf(field("organization", "Weekend Studio")),
                    emptyList(),
                    emptyList(),
                ),
            )
        model.seed(
            media,
            groups,
            state,
            FolderScan(sortLargestFirst(files), matchingExact(files), 1),
            ContactSnapshot(people, emptyMap(), limited = false),
        )
        assertTrue(model.ready.value)
    }
}
