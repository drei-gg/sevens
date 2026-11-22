package gg.seven.cleaner

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoReviewUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeRangeReviewRecreationAndSameCopyRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (ui.onAllNodesWithTag("seven-look-around").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("seven-look-around").performClick()
        val model = ViewModelProvider(ui.activity)[CleanerModel::class.java]
        if (InstrumentationRegistry.getArguments().getString("featureOrientation") == "landscape") {
            ui.runOnUiThread {
                ui.activity.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        ui.runOnUiThread {
            model.store.access.startTrial()
            model.store.refresh()
        }
        ui.waitUntil(10_000) { model.ready.value && model.store.paidFeaturesAvailable }
        val resolver = ui.activity.contentResolver
        instrumentation.uiAutomation.grantRuntimePermission(
            ui.activity.packageName,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        )
        val bytes = instrumentation.context.assets.open("video-profile.mp4").use { it.readBytes() }
        val uri =
            resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Video review fixture.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SevensVideoTests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            )!!
        var copy: android.net.Uri? = null
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            val item = runBlocking {
                model.photos.inventory().items.single { it.id == uri.toString() }
            }
            val state = runBlocking {
                model.repository.decide(
                    mapOf(item.id to gg.seven.cleaner.core.ReviewDecision.KEEP),
                    mapOf(item.id to item.revision),
                )
            }
            ui.onNodeWithTag("nav-Videos").performClick()
            ui.runOnUiThread {
                model.media.value = listOf(item)
                model.state.value = state
                model.openVideo(item)
            }
            ui.waitUntil(10_000) { model.videoReview.value != null && !model.busy.value }
            ui.waitForIdle()
            File(ui.activity.getExternalFilesDir(null), "video-review-tree.txt")
                .writeText(ui.onAllNodes(isRoot()).onLast().printToString())
            val initial = instrumentation.uiAutomation.takeScreenshot()
            File(ui.activity.getExternalFilesDir(null), "video-review-initial.png")
                .outputStream()
                .use { initial.compress(Bitmap.CompressFormat.PNG, 100, it) }
            initial.recycle()
            ui.enableAccessibilityChecks()
            try {
                ui.onAllNodes(isRoot()).onLast().tryPerformAccessibilityChecks()
            } finally {
                ui.disableAccessibilityChecks()
            }
            ui.onNodeWithTag("video.end").performScrollTo().performTextReplacement("0")
            ui.onNodeWithTag("video.prepare").assertIsNotEnabled()
            ui.onNodeWithTag("video.start").performScrollTo().performTextReplacement("1.250")
            ui.onNodeWithTag("video.end").performScrollTo().performTextReplacement("4.750")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithTag("video.start").assertTextContains("1.250")
            ui.onNodeWithTag("video.prepare").performScrollTo().performClick()
            ui.waitUntil(30_000) { model.videoReview.value?.candidate != null && !model.busy.value }
            val review = model.videoReview.value!!
            assertTrue(
                review.candidate!!.issues.joinToString(),
                review.candidate!!.issues.isEmpty(),
            )
            ui.onNodeWithTag("video.result")
                .performScrollTo()
                .assertTextContains("3.500", substring = true)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(ui.activity.getExternalFilesDir(null), "cleaner-video-review.png")
                .outputStream()
                .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            val ledger = File(ui.activity.filesDir, "Cleaner/review.json")
            val good = ledger.readBytes()
            ledger.writeText("broken ledger")
            ui.onNodeWithTag("editor-save").performClick()
            ui.waitUntil(15_000) {
                !model.busy.value && review.createdID != null && model.error.value != null
            }
            copy = android.net.Uri.parse(review.createdID!!)
            assertNull(review.savedID)
            ui.onNodeWithText("Keep editing").performClick()
            ledger.writeBytes(good)
            ui.onNodeWithTag("editor-save").performClick()
            ui.waitUntil(15_000) { !model.busy.value && review.savedID != null }
            assertEquals(copy.toString(), review.savedID)
            assertArrayEquals(
                review.candidate!!.output.readBytes(),
                resolver.openInputStream(copy)!!.use { it.readBytes() },
            )
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(
                gg.seven.cleaner.core.ReviewDecision.KEEP,
                runBlocking { model.repository.read().decisions[item.id] },
            )
            ui.onNodeWithTag("editor-save").performClick()
            assertFalse(review.source.exists())
        } finally {
            copy?.let { resolver.delete(it, null, null) }
            resolver.delete(uri, null, null)
            ui.runOnUiThread { model.closeVideo() }
        }
    }
}
