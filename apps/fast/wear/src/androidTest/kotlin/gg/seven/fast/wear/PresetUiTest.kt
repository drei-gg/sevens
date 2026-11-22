package gg.seven.fast.wear

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.fast.wire.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PresetUiTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun selectedPresetSurvivesTimeoutAndRetryUsesSameRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("fast.watch", 0).edit().clear().commit()
        val preset = WirePreset(UUID.randomUUID(), 1110, 330)
        val snapshot =
            WireSnapshot(
                UUID.randomUUID(),
                System.currentTimeMillis(),
                waterMilliliters = 0.0,
                canCreateRecords = true,
                presets =
                    listOf(12, 14, 16, 18, 20).map {
                        WirePreset(UUID.randomUUID(), it * 60, (24 - it) * 60)
                    } + listOf(WirePreset(UUID.randomUUID(), null, 480), preset),
            )
        val sent = mutableListOf<WireRequest>()
        var acknowledge = false
        val link =
            object : PhoneLink {
                override suspend fun reachable() = true

                override suspend fun request(bytes: ByteArray): ByteArray {
                    val request = WireCodec.decodeRequest(bytes)
                    sent += request
                    if (!acknowledge) throw java.io.IOException("Phone acknowledgement lost")
                    return WireCodec.encode(
                        WireReply(
                            requestId = request.id,
                            snapshot =
                                snapshot.copy(
                                    activeId = request.id,
                                    start = request.sentAt,
                                    goal = request.sentAt + 1110L * 60_000,
                                ),
                        )
                    )
                }
            }
        val model = WatchModel(context, link)
        ui.runOnUiThread {
            model.receive(WireCodec.encode(WireReply(snapshot = snapshot)))
            runBlocking { model.updateReachability() }
        }
        val shown = androidx.compose.runtime.mutableStateOf(model)
        ui.setContent { WatchHome(shown.value) }
        ui.onNodeWithTag("watch-list").performScrollToNode(hasText("Start fast"))
        ui.onNodeWithText("Start fast").performClick()
        ui.onNodeWithText("Saved presets").assertIsDisplayed()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "fast-watch-presets.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        ui.onAllNodes(hasScrollToIndexAction())
            .onLast()
            .performScrollToNode(hasTestTag("preset-${preset.id}"))
        ui.onNodeWithTag("preset-${preset.id}").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) { model.pending.value != null && !model.busy.value }
        assertEquals(preset.id, sent.single().presetId)
        assertEquals(2, sent.single().version)
        assertEquals(snapshot.revision, sent.single().revision)
        // A relaunched model must retry the exact action, not construct a new start command.
        val relaunched = WatchModel(context, link)
        assertEquals(sent.single(), relaunched.pending.value)
        acknowledge = true
        ui.runOnUiThread {
            shown.value = relaunched
            runBlocking { relaunched.updateReachability() }
        }
        ui.onNodeWithTag("watch-list").performScrollToNode(hasText("Retry same request"))
        ui.onNodeWithText("Retry same request").performClick()
        ui.waitUntil(10_000) { relaunched.pending.value == null && !relaunched.busy.value }
        assertNull(relaunched.pending.value)
        assertEquals(sent[0], sent[1])
        assertEquals(sent[0].id, relaunched.snapshot.value!!.activeId)
        assertEquals(
            1110L * 60_000,
            relaunched.snapshot.value!!.goal!! - relaunched.snapshot.value!!.start!!,
        )
        context.getSharedPreferences("fast.watch", 0).edit().clear().commit()
    }
}
