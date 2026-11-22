package gg.seven.vault

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.vault.core.VaultCredentialKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SPEC's UI tour on a device: create, recovery key, note, lock, unlock, pattern, expiry,
 * export.
 */
@RunWith(AndroidJUnit4::class)
class VaultUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app
        get() = ui.activity.application as VaultApplication

    private val model
        get() = androidx.lifecycle.ViewModelProvider(ui.activity)[VaultModel::class.java]

    private val secret = "several private testing words"

    @Before
    fun prepare() {
        val landscape =
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("featureOrientation") == "landscape"
        ui.runOnUiThread {
            ui.activity.requestedOrientation =
                if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        ui.waitUntil(5_000) {
            ui.activity.resources.configuration.orientation ==
                if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                else android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        runBlocking { app.repository.lock() }
        app.repository.root.deleteRecursively()
        runBlocking { app.repository.prepare() }
        app.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        ui.runOnUiThread {
            model.store.access.startTrial()
            model.store.refresh()
        }
        ui.waitForIdle()
        // First launch shows Before you buy; once the trial has started its button reads Done.
        if (
            ui.onAllNodesWithText("Your phone already has a Locked Folder.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            ui.onAllNodes(hasText("Look around") or hasText("Done")).onFirst().performClick()
        if (model.library != null) ui.runOnUiThread { model.lock() }
        ui.waitUntil(10_000) { !model.busy && model.store.paidFeaturesAvailable }
    }

    @Test
    fun noteTextSearchStaysUnlockedOnlyAndNeverWritesAnIndex() {
        createVault("Search archive", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.runOnUiThread {
            model.update {
                model.repository.addNote("Unrelated title", "Café STRAẞE hidden phrase", null)
            }
        }
        ui.waitUntil(10_000) { !model.busy && model.library?.items?.size == 1 }
        val note = model.library!!.items.single()
        val before =
            app.repository.root
                .walkTopDown()
                .filter { it.isFile }
                .associate { it.relativeTo(app.repository.root).path to it.readBytes().toList() }
        ui.onNodeWithTag("vault.search").performScrollTo().performTextInput("cafe strasse")
        ui.onAllNodesWithText("Unrelated title.txt").assertCountEquals(0)
        ui.onNodeWithTag("vault.searchNotes").performScrollTo().performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("vault.searchCoverage").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("vault.search").performImeAction()
        ui.onNodeWithTag("vault.library").performScrollToNode(hasText("Unrelated title.txt"))
        ui.onNodeWithText("Unrelated title.txt").assertIsDisplayed()
        ui.onNodeWithTag("vault.library").performScrollToNode(hasTestTag("vault.search"))
        ui.onNodeWithTag("vault.searchCoverage")
            .assertTextContains("Searched 1 note", substring = true)
        ui.onNodeWithTag("vault.searchCoverage").performScrollTo()
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
            ui.onNodeWithTag("vault.search").performScrollTo().performClick()
            ui.waitUntil(5_000) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.hasWindowFocus() &&
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
            }
            capture("vault-note-search-ime-before-bounds")
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
                    ui.onNodeWithTag("vault.search").fetchSemanticsNode().boundsInRoot.bottom <=
                        window.height - ime
            }
            capture("vault-note-search-keyboard")
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
        } finally {
            if (keyboardSetting == "null")
                shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $keyboardSetting")
            shell("input keyevent 4")
        }

        ui.onNodeWithTag("vault.search").performImeAction()
        ui.onNodeWithTag("vault.searchCoverage").performScrollTo()
        ui.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        android.os.SystemClock.sleep(500)
        capture("vault-note-search")
        assertEquals(
            before,
            app.repository.root
                .walkTopDown()
                .filter { it.isFile }
                .associate { it.relativeTo(app.repository.root).path to it.readBytes().toList() },
        )
        org.junit.Assert.assertTrue(app.repository.temporary.walkTopDown().none { it.isFile })
        val scoped = runBlocking { app.repository.searchNotes("hidden", favorites = true) }
        assertEquals(emptyList<java.util.UUID>(), scoped.ids)
        ui.onNodeWithContentDescription("Lock").performClick()
        ui.waitUntil(10_000) { model.library == null && !model.busy }
        ui.onNodeWithTag("vault.credential").performScrollTo().performTextInput(secret)
        ui.onNodeWithTag("vault.credential").performImeAction()
        ui.waitUntil(10_000) { model.library != null && !model.busy }
        ui.onNodeWithTag("vault.search")
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString(""),
                )
            )
        ui.onNodeWithTag("vault.searchNotes").assertIsNotSelected()
        runBlocking {
            app.repository.updateItem(note.id, trashed = true)
            assertEquals(0, app.repository.searchNotes("hidden").ids.size)
            assertEquals(listOf(note.id), app.repository.searchNotes("hidden", trash = true).ids)
            app.repository.updateItem(note.id, trashed = false)
            app.repository.editNote(note.id, "replaced")
            assertEquals(0, app.repository.searchNotes("hidden").ids.size)
        }
    }

    private fun waitFor(text: String, timeout: Long = 30_000) {
        try {
            ui.waitUntil(timeout) {
                ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            // What was on screen instead, for the test log.
            val roots = ui.onAllNodes(isRoot()).fetchSemanticsNodes().size
            for (i in 0 until roots) ui.onAllNodes(isRoot())[i].printToLog("VaultUiTest")
            throw e
        }
    }

    private fun createVault(name: String, kind: VaultCredentialKind, enter: (String) -> Unit) {
        ui.onNodeWithText("Create a vault").performScrollTo().performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("vault.newName").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("vault.newName").performTextInput(name)
        if (kind == VaultCredentialKind.PATTERN)
            ui.onAllNodesWithTag("choice-Pattern").onLast().performClick()
        enter("vault.newPassword")
        enter("vault.repeatPassword")
        ui.onNodeWithText("I understand there is no account reset").performScrollTo().performClick()
        ui.onNodeWithTag("editor-save")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.waitUntil(30_000) {
            ui.onAllNodesWithTag("vault.recoveryKey").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("I’ve saved my recovery key").performScrollTo().performClick()
        ui.onNodeWithTag("vault.continue").performScrollTo().performClick()
        waitFor(name)
    }

    private fun capture(name: String) {
        // Synthetic fixture only: activity recreation restores FLAG_SECURE, and dialogs inherit it.
        // Clear every test window immediately before capture; production keeps its secure policy.
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            fun clear(view: android.view.View) {
                (view as? androidx.compose.ui.window.DialogWindowProvider)
                    ?.window
                    ?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                if (view is android.view.ViewGroup)
                    for (index in 0 until view.childCount) clear(view.getChildAt(index))
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::clear)
        }
        ui.waitForIdle()
        android.os.SystemClock.sleep(
            300
        ) // Let SurfaceFlinger apply the test-only secure-window change.
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
    fun recognitionQueueIncludesEveryPdfPageRejectsLongPdfAndCompletesExplicitly() {
        createVault("PDF queue", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        val small = File(app.cacheDir, "Batch pages.pdf")
        val large = File(app.cacheDir, "Long batch.pdf")
        fun pdf(file: File, count: Int) {
            val document = android.graphics.pdf.PdfDocument()
            val paint =
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    textSize = 28f
                }
            try {
                repeat(count) { index ->
                    val page =
                        document.startPage(
                            android.graphics.pdf.PdfDocument.PageInfo.Builder(612, 792, index + 1)
                                .create()
                        )
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    page.canvas.drawText("BATCH PAGE ${index + 1} PRIVATE 7429", 40f, 150f, paint)
                    document.finishPage(page)
                }
                file.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
        }
        pdf(small, 2)
        pdf(large, 21)
        try {
            ui.runOnUiThread {
                model.update {
                    app.repository.importFile(small.path, small.name, "com.adobe.pdf")
                    app.repository.importFile(large.path, large.name, "com.adobe.pdf")
                }
            }
            ui.waitUntil(30_000) { !model.busy && model.library?.items?.size == 2 }
            val originals = model.library!!.items
            ui.runOnUiThread { model.prepareRecognitionQueue(originals.map { it.id }.toSet()) }
            val queue = model.presentation as RecognitionQueue
            ui.onNodeWithTag("ocr-batch-start").performScrollTo().performClick()
            ui.waitUntil(60_000) { !model.busy && queue.ready }
            org.junit.Assert.assertTrue(queue.text.contains("BATCH PAGE 1 PRIVATE 7429"))
            org.junit.Assert.assertTrue(queue.text.contains("BATCH PAGE 2 PRIVATE 7429"))
            ui.onNodeWithTag("editor-save").performClick()
            ui.waitUntil(60_000) { !model.busy && queue.failure != null }
            ui.onNodeWithTag("ocr-batch-error")
                .assertTextContains("individual page-range", substring = true)
            ui.onNodeWithTag("ocr-batch-skip").performScrollTo().performClick()
            ui.onNodeWithText("Review complete").assertExists()
            assertEquals(1, queue.saved)
            assertEquals(1, queue.skipped)
            assertEquals("", queue.text)
            assertEquals(3, model.library!!.items.size)
            val note = model.library!!.items.single { it.contentType == "public.plain-text" }
            org.junit.Assert.assertTrue(
                runBlocking { app.repository.readNote(note.id) }.contains("Page 2")
            )
            ui.onNodeWithTag("editor-cancel").performClick()
            ui.runOnUiThread {
                model.prepareRecognitionQueue(model.library!!.items.map { it.id }.toSet())
            }
            ui.onNodeWithText("Select only images and PDFs.", substring = true).assertExists()
            assertNull(model.presentation)
        } finally {
            small.delete()
            large.delete()
        }
    }

    @Test
    fun selectedRecognitionQueueReviewsSavesSkipsAndLocksWithoutChangingOriginals() {
        createVault("Batch archive", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        val source = File(app.cacheDir, "Batch receipt.png")
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                1600,
                800,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = 76f
            }
        canvas.drawText("BATCH PRIVATE RECEIPT 7429", 60f, 220f, paint)
        source.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val broken = File(app.cacheDir, "Broken.png")
        broken.writeText("not an image")
        try {
            ui.runOnUiThread {
                model.update {
                    app.repository.importFile(source.path, "First receipt.png", "public.png")
                    app.repository.importFile(broken.path, "Broken.png", "public.png")
                    app.repository.importFile(source.path, "Last receipt.png", "public.png")
                }
            }
            ui.waitUntil(30_000) { !model.busy && model.library?.items?.size == 3 }
            val originals = model.library!!.items
            val before =
                originals.associate {
                    it.id to
                        runBlocking { app.repository.export(it.id) }
                            .let { f -> f.readBytes().also { f.delete() } }
                }
            ui.onNodeWithTag("vault.library").performScrollToNode(hasText("First receipt.png"))
            ui.onNodeWithText("First receipt.png").performTouchInput { longClick() }
            ui.onNodeWithContentDescription("Select all shown").performClick()
            ui.onNodeWithTag("ocr-batch-open").performClick()
            val queue = model.presentation as RecognitionQueue
            assertEquals(3, queue.plan.items.size)
            ui.onNodeWithTag("ocr-batch-start").performScrollTo().performClick()
            var saved = false
            while (queue.item != null) {
                ui.waitUntil(60_000) { !model.busy && (queue.ready || queue.failure != null) }
                if (queue.failure != null) {
                    ui.onNodeWithText("Retry this file").performScrollTo().performClick()
                    ui.waitUntil(60_000) { !model.busy && queue.failure != null }
                    ui.onNodeWithTag("ocr-batch-skip").performScrollTo().performClick()
                } else if (!saved) {
                    ui.onNodeWithTag("ocr-batch-text").assertTextContains("7429", substring = true)
                    ui.onNodeWithTag("ocr-batch-name")
                        .performScrollTo()
                        .performTextReplacement("Reviewed batch note")
                    ui.onNodeWithTag("ocr-batch-text")
                        .performScrollTo()
                        .performTextReplacement("Reviewed encrypted batch text")
                    ui.activityRule.scenario.recreate()
                    ui.onNodeWithTag("ocr-batch-text")
                        .assertTextContains("Reviewed encrypted batch text")
                    ui.onNodeWithTag("editor-save").performClick()
                    ui.waitUntil(30_000) { queue.saved == 1 }
                    saved = true
                } else {
                    val automation =
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                            .uiAutomation
                    fun shell(command: String) =
                        automation.executeShellCommand(command).use {
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                                .bufferedReader()
                                .readText()
                                .trim()
                        }
                    val previousKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
                    try {
                        shell("settings put secure show_ime_with_hard_keyboard 1")
                        ui.onNodeWithTag("ocr-batch-name").performScrollTo().performClick()
                        ui.waitUntil(5_000) {
                            android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                                window ->
                                val insets =
                                    androidx.core.view.ViewCompat.getRootWindowInsets(window)
                                window.hasWindowFocus() &&
                                    insets?.isVisible(
                                        androidx.core.view.WindowInsetsCompat.Type.ime()
                                    ) == true &&
                                    ui.onNodeWithTag("ocr-batch-name")
                                        .fetchSemanticsNode()
                                        .boundsInRoot
                                        .bottom <=
                                        window.height -
                                            insets
                                                .getInsets(
                                                    androidx.core.view.WindowInsetsCompat.Type.ime()
                                                )
                                                .bottom
                            }
                        }
                        capture("vault-batch-keyboard")
                        shell("input keyevent 4")
                    } finally {
                        if (previousKeyboard == "null")
                            shell("settings delete secure show_ime_with_hard_keyboard")
                        else
                            shell(
                                "settings put secure show_ime_with_hard_keyboard $previousKeyboard"
                            )
                    }
                    ui.waitUntil(5_000) {
                        android.view.inspector.WindowInspector.getGlobalWindowViews()
                            .filter { it.hasWindowFocus() }
                            .all {
                                androidx.core.view.ViewCompat.getRootWindowInsets(it)
                                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) !=
                                    true
                            }
                    }
                    ui.onNodeWithTag("ocr-batch-counts").performScrollTo()
                    capture("vault-batch-review")
                    ui.runOnIdle {
                        val focused =
                            android.view.inspector.WindowInspector.getGlobalWindowViews().single {
                                it.hasWindowFocus()
                            }
                        com.google.android.apps.common.testing.accessibility.framework.integrations
                            .espresso
                            .AccessibilityValidator()
                            .setThrowExceptionFor(
                                com.google.android.apps.common.testing.accessibility.framework
                                    .AccessibilityCheckResult
                                    .AccessibilityCheckResultType
                                    .ERROR
                            )
                            .check(focused)
                    }
                    ui.onNodeWithTag("editor-lock").performClick()
                    ui.waitUntil(30_000) { !model.busy && model.library == null }
                    assertEquals("", queue.text)
                    assertNull(model.presentation)
                    break
                }
            }
            org.junit.Assert.assertTrue(saved)
            ui.runOnUiThread { model.unlock(secret, false, VaultCredentialKind.PASSPHRASE) }
            ui.waitUntil(30_000) { !model.busy && model.library != null }
            val note = model.library!!.items.single { it.contentType == "public.plain-text" }
            assertEquals(
                "Reviewed encrypted batch text",
                runBlocking { app.repository.readNote(note.id) },
            )
            before.forEach { (id, bytes) ->
                val file = runBlocking { app.repository.export(id) }
                org.junit.Assert.assertArrayEquals(bytes, file.readBytes())
                file.delete()
            }
            ui.runOnUiThread { model.prepareRecognitionQueue(originals.map { it.id }.toSet()) }
            ui.onNodeWithTag("ocr-batch-start").performScrollTo().performClick()
            ui.onNodeWithTag("ocr-batch-stop").performScrollTo().performClick()
            if (ui.onAllNodesWithText("Stop and discard").fetchSemanticsNodes().isNotEmpty())
                ui.onNodeWithText("Stop and discard").performClick()
            ui.waitUntil(60_000) { !model.busy }
            val stopped = model.presentation as RecognitionQueue
            org.junit.Assert.assertTrue(stopped.stopped)
            assertEquals("", stopped.text)
            assertEquals(4, model.library!!.items.size)
            org.junit.Assert.assertTrue(app.repository.temporary.walkTopDown().none { it.isFile })
        } finally {
            source.delete()
            broken.delete()
        }
    }

    @Test
    fun recognizedTextReviewSavesEncryptedNoteAndLockDiscardsReview() {
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
        }

        createVault("Text archive", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        val image =
            android.graphics.Bitmap.createBitmap(
                1600,
                800,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        val canvas = android.graphics.Canvas(image)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = 76f
                typeface =
                    android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
        canvas.drawText("PRIVATE ARCHIVE 7429", 100f, 220f, paint)
        val source = File(app.cacheDir, "Receipt.png")
        source.outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        val original = source.readBytes()
        try {
            ui.runOnUiThread {
                model.update { app.repository.importFile(source.path, source.name, "public.png") }
            }
            waitFor("Receipt.png")
            val item = model.library!!.items.single()
            ui.onNodeWithText("Receipt.png").performClick()
            ui.onNodeWithText("Recognize text").performScrollTo().performClick()
            ui.waitUntil(60_000) { !model.busy && model.presentation is Presentation.TextReview }
            ui.onNodeWithTag("ocr-text")
                .assertTextContains("PRIVATE ARCHIVE 7429", substring = true)
            ui.enableAccessibilityChecks()
            ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
            capture("vault-ocr-review")
            ui.onNodeWithTag("ocr-text")
                .performScrollTo()
                .performTextReplacement("Reviewed private archive 7429")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithTag("ocr-text").assertTextContains("Reviewed private archive 7429")
            ui.onNodeWithTag("editor-save").performClick()
            waitFor("Receipt text.txt")
            val note = model.library!!.items.single { it.id != item.id }
            assertEquals(
                "Reviewed private archive 7429",
                runBlocking { app.repository.readNote(note.id) },
            )
            org.junit.Assert.assertArrayEquals(original, source.readBytes())
            val exported = runBlocking { app.repository.export(item.id) }
            org.junit.Assert.assertArrayEquals(original, exported.readBytes())
            exported.delete()
            ui.runOnUiThread { model.recognizeText(item) }
            ui.waitUntil(60_000) { !model.busy && model.presentation is Presentation.TextReview }
            ui.onNodeWithTag("editor-lock").performClick()
            ui.waitUntil(30_000) { !model.busy && model.library == null }
            ui.waitUntil(30_000) { app.repository.temporary.listFiles().isNullOrEmpty() }
            assertNull(model.presentation)
            ui.runOnUiThread { model.unlock(secret, false, VaultCredentialKind.PASSPHRASE) }
            waitFor("Receipt text.txt")
            assertEquals(
                "Reviewed private archive 7429",
                runBlocking { app.repository.readNote(note.id) },
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun pdfRangeRecognitionPreservesOriginalAndEncryptsReviewedNote() {
        val landscape =
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("featureOrientation") == "landscape"
        val requested =
            if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ui.runOnUiThread { ui.activity.requestedOrientation = requested }
        ui.waitUntil(5_000) {
            ui.activity.resources.configuration.orientation ==
                if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                else android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        createVault("PDF archive", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        val source = File(app.cacheDir, "Selected pages.pdf")
        val pdf = android.graphics.pdf.PdfDocument()
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = 28f
            }
        for ((index, text) in
            listOf("OUTSIDE FIRST PAGE", "PRIVATE ARCHIVE 7429", "OUTSIDE LAST PAGE").withIndex()) {
            val page =
                pdf.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(612, 792, index + 1).create()
                )
            page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.drawText(text, 40f, 150f, paint)
            pdf.finishPage(page)
        }
        source.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        val original = source.readBytes()
        try {
            ui.runOnUiThread {
                model.update {
                    app.repository.importFile(source.path, source.name, "com.adobe.pdf")
                }
            }
            waitFor(source.name)
            val item = model.library!!.items.single()
            ui.onNodeWithText(source.name).performClick()
            ui.onNodeWithText("Recognize text").performScrollTo().performClick()
            ui.waitUntil(30_000) { !model.busy && model.presentation is Presentation.PdfRange }
            ui.onNodeWithTag("ocr-first").performTextReplacement("0")
            ui.onNodeWithTag("editor-save").assertIsNotEnabled()
            ui.onNodeWithTag("ocr-first").performTextReplacement("2")
            ui.onNodeWithTag("ocr-last").performScrollTo().performTextReplacement("2")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithTag("ocr-first").assertTextContains("2")
            ui.onNodeWithTag("ocr-last").assertTextContains("2")
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
                ui.onNodeWithTag("ocr-last").performScrollTo().performClick()
                ui.waitUntil(5_000) {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                        it.hasWindowFocus() &&
                            androidx.core.view.ViewCompat.getRootWindowInsets(it)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) ==
                                true
                    }
                }
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
                        ui.onNodeWithTag("ocr-last").fetchSemanticsNode().boundsInRoot.bottom <=
                            window.height - ime
                }
                capture("vault-pdf-range-keyboard")
                ui.runOnIdle {
                    val focused =
                        android.view.inspector.WindowInspector.getGlobalWindowViews().single {
                            it.hasWindowFocus()
                        }
                    com.google.android.apps.common.testing.accessibility.framework.integrations
                        .espresso
                        .AccessibilityValidator()
                        .setThrowExceptionFor(
                            com.google.android.apps.common.testing.accessibility.framework
                                .AccessibilityCheckResult
                                .AccessibilityCheckResultType
                                .ERROR
                        )
                        .check(focused)
                }
            } finally {
                if (keyboardSetting == "null")
                    shell("settings delete secure show_ime_with_hard_keyboard")
                else shell("settings put secure show_ime_with_hard_keyboard $keyboardSetting")
                shell("input keyevent 4")
            }

            ui.enableAccessibilityChecks()
            ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
            capture("vault-pdf-range")
            ui.onNodeWithTag("editor-save").performClick()
            ui.waitUntil(60_000) { !model.busy && model.presentation is Presentation.TextReview }
            val draft = model.presentation as Presentation.TextReview
            org.junit.Assert.assertTrue(draft.text.contains("Page 2"))
            org.junit.Assert.assertTrue(draft.text.contains("PRIVATE ARCHIVE 7429"))
            org.junit.Assert.assertFalse(draft.text.contains("OUTSIDE"))
            capture("vault-pdf-review")
            ui.onNodeWithTag("editor-save").performClick()
            waitFor("Selected pages text.txt")
            val note = model.library!!.items.single { it.id != item.id }
            org.junit.Assert.assertTrue(
                runBlocking { app.repository.readNote(note.id) }.contains("PRIVATE ARCHIVE 7429")
            )
            val exported = runBlocking { app.repository.export(item.id) }
            org.junit.Assert.assertArrayEquals(original, exported.readBytes())
            exported.delete()
            ui.runOnUiThread { model.recognizeText(item) }
            ui.waitUntil(30_000) { !model.busy && model.presentation is Presentation.PdfRange }
            ui.onNodeWithTag("editor-lock").performClick()
            ui.waitUntil(30_000) { !model.busy && model.library == null }
            ui.waitUntil(30_000) { app.repository.temporary.listFiles().isNullOrEmpty() }
            assertNull(model.presentation)
            ui.runOnUiThread { model.unlock(secret, false, VaultCredentialKind.PASSPHRASE) }
            waitFor("Selected pages text.txt")
            org.junit.Assert.assertTrue(
                runBlocking { app.repository.readNote(note.id) }.contains("PRIVATE ARCHIVE 7429")
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun failedCredentialChangeKeepsDraftForRetry() {
        createVault("Credential review", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        val reserved = "already used by another vault"
        runBlocking {
            app.repository.create("Other vault", reserved, VaultCredentialKind.PASSPHRASE)
            app.repository.lock()
            app.repository.unlock(secret, false, VaultCredentialKind.PASSPHRASE)
        }
        ui.onNodeWithContentDescription("Library options").performClick()
        ui.onNodeWithText("Settings", substring = false).performClick()
        val first = ui.onNodeWithTag("vault.changeCredential")
        val repeated = ui.onNodeWithTag("vault.repeatChangeCredential")
        first.performScrollTo().performTextInput(reserved)
        repeated.performScrollTo().performTextInput(reserved)
        ui.onNodeWithText("Change unlock secret").performScrollTo().performClick()
        ui.waitUntil(15_000) { !model.busy && model.error != null }
        ui.onNodeWithText("OK").performClick()
        first.assertTextContains(reserved)
        repeated.assertTextContains(reserved)
        capture("coverage-vault-credential-retry")
        val replacement = "new independent testing words"
        first.performScrollTo().performTextReplacement(replacement)
        repeated.performScrollTo().performTextReplacement(replacement)
        ui.onNodeWithText("Change unlock secret").performScrollTo().performClick()
        waitFor("Unlock secret changed.")
        ui.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        ui.onNodeWithText("Unlock secret changed.", substring = true).assertIsDisplayed()
        // Insets report the hidden target before the system IME's closing animation ends.
        // Let that platform animation settle before recording the final visual state.
        android.os.SystemClock.sleep(400)
        capture("coverage-vault-credential-success")
        assertEquals(
            "",
            first
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
                .text,
        )
        assertEquals(
            "",
            repeated
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
                .text,
        )
        runBlocking {
            app.repository.lock()
            assertEquals(
                "Credential review",
                app.repository.unlock(replacement, false, VaultCredentialKind.PASSPHRASE).name,
            )
        }
    }

    @Test
    fun m3ReviewLibraryControls() {
        createVault("Library review", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        ui.onNodeWithContentDescription("Library options").performClick()
        ui.enableAccessibilityChecks()
        ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
        capture("m3-vault-library-menu")
        ui.onNodeWithText("Select", substring = false).performScrollTo().performClick()
        ui.onNodeWithContentDescription("Done selecting").performClick()
        ui.onNodeWithTag("vault.sort").performClick()
        ui.onNodeWithText("Name", substring = false).performClick()
        ui.waitUntil(5_000) { model.library?.sort == gg.seven.vault.core.VaultOrder.NAME }
        ui.onNodeWithTag("vault.favorites").performClick()
        ui.onNodeWithTag("vault.favorites").assertIsSelected()
        ui.onNodeWithTag("vault.favorites").performClick()
        ui.onNodeWithTag("vault.favorites").assertIsNotSelected()
        capture("m3-vault-library-controls")
    }

    @Test
    fun previewProtectsUnsavedNoteEdits() {
        createVault("Draft guard", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.runOnUiThread { model.update { app.repository.addNote("Original", "Saved text", null) } }
        waitFor("Original.txt")
        ui.onNodeWithText("Original.txt").performClick()
        waitFor("Note text")
        ui.onNodeWithTag("vault.editNote").performScrollTo().performTextReplacement("Unsaved text")
        ui.onNodeWithText("Preview", substring = false).performScrollTo().performClick()
        ui.onNodeWithText("Keep editing").performClick()
        ui.onNodeWithTag("vault.editNote").assertTextContains("Unsaved text")
        ui.onNodeWithText("Export original").performScrollTo().performClick()
        ui.onNodeWithText("Keep editing").performClick()
        ui.onNodeWithText("Preview", substring = false).performScrollTo().performClick()
        ui.onNodeWithText("Discard", substring = false).performClick()
        waitFor("Saved text")
        ui.onNodeWithText("Unsaved text").assertDoesNotExist()
    }

    @Test
    fun longPdfPreviewIncludesPagesAfterFifty() {
        createVault("Documents", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        val file = File(app.cacheDir, "Long.pdf")
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            repeat(51) { index ->
                val page =
                    pdf.startPage(
                        android.graphics.pdf.PdfDocument.PageInfo.Builder(120, 180, index + 1)
                            .create()
                    )
                page.canvas.drawText("Page ${index + 1}", 10f, 30f, android.graphics.Paint())
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        ui.runOnUiThread {
            model.update { app.repository.importFile(file.path, "Long.pdf", "com.adobe.pdf") }
        }
        waitFor("Long.pdf")
        ui.onNodeWithText("Long.pdf").performClick()
        ui.onNodeWithText("Preview", substring = false).performScrollTo().performClick()
        waitFor("Page 1 of 51")
        ui.onNodeWithTag("vault.pdf.pages").performScrollToIndex(50)
        ui.waitUntil(10_000) {
            ui.onAllNodesWithContentDescription("Page 51").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Page 51 of 51").assertIsDisplayed()
        capture("coverage-vault-pdf-last-page")
        ui.onNodeWithContentDescription("Done").performClick()
        ui.waitUntil(10_000) { app.repository.temporary.listFiles().isNullOrEmpty() }
        file.delete()
    }

    @Test
    fun selectionToolbarMovesToTrashAndUndoRestores() {
        createVault("Selection", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.onNodeWithTag("vault.add").performClick()
        ui.onNodeWithTag("vault.add.note").performClick()
        ui.onNodeWithTag("vault.noteName").performTextInput("Archive note")
        ui.onNodeWithTag("vault.noteText").performTextInput("Retain the original")
        ui.onNodeWithTag("editor-save").performClick()
        waitFor("Archive note.txt")
        ui.onNodeWithContentDescription("Library options").performClick()
        ui.onNodeWithText("Select", substring = false).performScrollTo().performClick()
        ui.onNodeWithContentDescription("Move selected to trash").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Select all shown").performClick()
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        capture("vault-selection-toolbar")
        ui.onNodeWithContentDescription("Move selected to trash").performClick()
        ui.onNodeWithText("Undo").performClick()
        waitFor("Archive note.txt")
        ui.onNodeWithContentDescription("Library options").performClick()
        ui.onNodeWithText("Select", substring = false).performClick()
        ui.onNodeWithContentDescription("Select all shown").performClick()
        ui.onNodeWithContentDescription("Move selected to trash").performClick()
        ui.waitUntil(10_000) { model.library?.items?.single()?.deleted != null }
        ui.onNodeWithContentDescription("Library options").performClick()
        ui.onNodeWithText("Recently Deleted", substring = false).performClick()
        ui.onNodeWithText("Archive note.txt").assertIsDisplayed()
        capture("coverage-vault-trash")
        ui.onNodeWithContentDescription("Library options").performClick()
        ui.onNodeWithText("Select", substring = false).performClick()
        ui.onNodeWithContentDescription("Select all shown").performClick()
        ui.onNodeWithContentDescription("Restore selected").performClick()
        ui.waitUntil(10_000) { model.library?.items?.single()?.deleted == null }
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Archive note.txt").assertIsDisplayed()
    }

    @Test
    fun noteDiscardRequiresAChoiceAndDoesNotCreateAFile() {
        createVault("Drafts", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        ui.onNodeWithTag("vault.add").performClick()
        ui.onNodeWithTag("vault.add.note").performClick()
        ui.onNodeWithTag("vault.noteName").performTextInput("Unsaved")
        ui.onNodeWithTag("vault.noteText").performTextInput("Keep this draft while editing")
        ui.onNodeWithContentDescription("Close").performClick()
        ui.onNodeWithText("Keep editing").performClick()
        ui.onNodeWithTag("vault.noteText").assertTextContains("Keep this draft while editing")
        ui.onNodeWithContentDescription("Close").performClick()
        ui.onNodeWithText("Discard", substring = false).performClick()
        ui.onNodeWithText("Unsaved.txt").assertDoesNotExist()
        // Test fixture only: production keeps FLAG_SECURE throughout the open-vault session.
        ui.runOnUiThread {
            ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        capture("vault-library")
    }

    @Test
    fun createNoteLockUnlockAndRecover() {
        createVault("Travel", VaultCredentialKind.PASSPHRASE) {
            ui.onNodeWithTag(it).performScrollTo().performTextInput(secret)
        }
        val recovery = runBlocking { app.repository.replaceRecoveryKey() } // a key this test knows
        ui.onNodeWithTag("vault.add").performClick()
        ui.onNodeWithTag("vault.add.note").performClick()
        ui.onNodeWithTag("vault.noteName").performTextInput("Checklist")
        ui.onNodeWithTag("vault.noteText").performTextInput("Passport")
        ui.onNodeWithTag("editor-save")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("Checklist.txt")
        ui.onNodeWithTag("vault.lock").performClick()
        waitFor("Yours to keep.")
        assertNull(model.library)
        ui.onNodeWithTag("vault.credential").performTextInput("not the right words")
        ui.onNodeWithTag("vault.unlock")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("didn’t open a vault")
        ui.onNodeWithText("OK").performClick()
        ui.onNodeWithTag("vault.credential").performScrollTo().performTextInput(secret)
        // Unlock remains reachable from the IME when the button is below the keyboard.
        ui.onNodeWithTag("vault.credential").performImeAction()
        waitFor("Checklist.txt")
        // Leaving the app locks it; coming back shows the lock screen.
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("Yours to keep.")
        ui.onNodeWithText("Use a recovery key").performClick()
        ui.onNodeWithTag("vault.credential").performScrollTo().performTextInput(recovery)
        ui.onNodeWithTag("vault.credential").performImeAction()
        waitFor("Checklist.txt")
        ui.onNodeWithText("Checklist.txt").performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("vault.editNote").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("vault.editNote").assertTextContains("Passport")
    }

    @Test
    fun patternVaultFromTappedDots() {
        val dots = listOf(0, 1, 2, 3, 4, 9, 14, 19)
        createVault("Pattern library", VaultCredentialKind.PATTERN) { tag ->
            dots.forEach {
                ui.onNodeWithTag("$tag.dot.$it")
                    .performScrollTo()
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            }
        }
        ui.onNodeWithTag("vault.lock").performClick()
        waitFor("Open your vault")
        ui.onNodeWithTag("choice-Pattern").performScrollTo().performClick()
        // Tapping 0 then 4 fills in the crossed dots 1-3, as the core canonicalises.
        listOf(0, 4, 9, 14, 19).forEach {
            ui.onNodeWithTag("vault.credential.dot.$it")
                .performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }
        ui.onNodeWithTag("vault.unlock")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("Pattern library")
        assertEquals("Pattern library", model.library?.name)
    }

    @Test
    fun expiredTrialStillOpensAndExportsButGatesAdding() {
        val photo =
            File(app.cacheDir, "Weekend.png").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        runBlocking {
            app.repository.create("Archive", secret, VaultCredentialKind.PASSPHRASE)
            app.repository.importFile(photo.path, "Weekend.png", "public.png")
            app.repository.lock()
        }
        photo.delete()
        app.getSharedPreferences("seven-trial", 0)
            .edit()
            .putLong("start", System.currentTimeMillis() - 8 * 86_400_000L)
            .commit()
        ui.runOnUiThread { model.store.refresh() }
        ui.waitForIdle()
        org.junit.Assert.assertFalse("trial expired", model.store.paidFeaturesAvailable)
        if (ui.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithText("Not now").performClick()
        ui.onNodeWithTag("vault.credential").performTextInput(secret)
        ui.onNodeWithTag("vault.unlock")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("Weekend.png")
        // Adding is part of the unlock.
        ui.onNodeWithTag("vault.add").performClick()
        ui.onNodeWithTag("vault.add.note").performClick()
        ui.waitForIdle()
        waitFor("Write a note is part of the unlock")
        ui.onNodeWithText("Not now").performScrollTo().performClick()
        // Export is not.
        ui.onNodeWithText("Weekend.png").performClick()
        ui.onNodeWithText("Export original").performScrollTo().performClick()
        waitFor("Original ready")
        val files = (model.presentation as Presentation.Files).files
        assertEquals(4096L, files.single().length())
        ui.onNodeWithText("Done").performClick()
        ui.waitUntil(10_000) { app.repository.temporary.listFiles().isNullOrEmpty() }
    }
}
