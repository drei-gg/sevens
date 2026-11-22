package gg.seven.bloom.wear

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.bloom.wire.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchUiTest {
    @get:Rule val rule = createComposeRule()

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
            .outputStream()
            .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test
    fun doseShowsPendingThenClearsOnlyAfterMatchingPhoneAcknowledgement() {
        val now = Instant.now().epochSecond
        val dose = WatchDose("dose", "plan", "2026-11-18", now, "Your reminder", "active", "due")
        val snapshot =
            WatchSnapshot(generated = now, revision = 1, enabled = true, doses = listOf(dose))
        var disk = WatchCodec.encode(WatchStored(snapshot))
        var connected = false
        val model =
            WatchModel(
                object : WatchPersistence {
                    override fun read() = disk

                    override suspend fun save(value: String) {
                        disk = value
                    }
                },
                object : PhoneLink {
                    override suspend fun stored() = emptyList<ByteArray>()

                    override suspend fun request(bytes: ByteArray): ByteArray {
                        check(connected)
                        return WatchCodec.encode(
                            WatchReply(
                                request_id = WatchCodec.decodeRequest(bytes).id,
                                snapshot =
                                    snapshot.copy(
                                        revision = 2,
                                        doses = listOf(dose.copy(status = "taken")),
                                    ),
                            )
                        )
                    }
                },
            )
        rule.setContent { WatchHome(model) }
        rule.onNodeWithTag("watch-list").performScrollToNode(hasText("Taken"))
        rule.onNodeWithText("Taken").assertIsEnabled()
        capture("bloom-wear-dose")
        rule.onNodeWithText("Taken").performClick()
        rule.waitUntil(5000) { model.state.value.pending != null && !model.busy.value }
        rule
            .onNodeWithTag("watch-list")
            .performScrollToNode(hasText("Pending — not saved on phone yet."))
        rule.onNodeWithText("Pending — not saved on phone yet.").assertIsDisplayed()
        capture("bloom-wear-pending")
        rule.onNodeWithTag("watch-list").performScrollToNode(hasText("Clear pending action"))
        rule.onNodeWithText("Clear pending action").performClick()
        rule.onNodeWithText("Clear pending action?").assertIsDisplayed()
        val cancel = rule.onNodeWithTag("clear-pending-cancel")
        if (rule.onAllNodesWithTag("clear-pending-cancel").fetchSemanticsNodes().isEmpty() || !cancel.isDisplayed())
            rule.onAllNodes(isRoot()).onLast().performTouchInput { swipeUp() }
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "confirmation-semantics.txt").writeText(rule.onAllNodes(isRoot()).onLast().printToString())
        capture("bloom-wear-confirmation")
        rule.onNodeWithTag("clear-pending-cancel").assertIsDisplayed().performClick()
        assertNotNull(model.state.value.pending)
        connected = true
        rule.onNodeWithTag("watch-list").performScrollToNode(hasText("Sync with phone"))
        rule.onNodeWithText("Sync with phone").performClick()
        rule.waitUntil(5000) { model.state.value.pending == null && !model.busy.value }
        assertEquals("taken", model.state.value.snapshot!!.doses.single().status)
        rule.runOnIdle {
            runBlocking {
                model.receive(
                    WatchCodec.encode(
                        WatchReply(snapshot = WatchSnapshot(generated = now + 1, enabled = false))
                    )
                )
            }
        }
        rule
            .onNodeWithTag("watch-list")
            .performScrollToNode(
                hasText("Watch companion is off. Enable it on your phone to share a snapshot.")
            )
        capture("bloom-wear-off")
        assertFalse(disk.contains("Your reminder"))
    }
}
