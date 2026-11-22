package gg.seven.bloom

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BloomWidgetTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun projectionHonorsOptInAndDiscreetMode() {
        val archive = JSONObject(gg.seven.bloom.core.bloomEmpty())
        val profile = archive.getJSONObject("profile")
        val dashboard =
            JSONObject("""{"cycle_day":4,"reminders":[{"at":90},{"at":140},{"at":120}]}""")
        assertEquals(
            BloomWidgetSnapshot(false, null, null),
            BloomWidgetSnapshot.from(archive, dashboard, 100),
        )
        profile.put("widgets_enabled", true).put("discreet", true)
        assertEquals(
            BloomWidgetSnapshot(true, null, 120),
            BloomWidgetSnapshot.from(archive, dashboard, 100),
        )
        profile.put("discreet", false)
        assertEquals(
            BloomWidgetSnapshot(true, 4, 120),
            BloomWidgetSnapshot.from(archive, dashboard, 100),
        )
        assertNull(BloomWidgetSnapshot.from(archive, dashboard, 200).nextReminder)
    }

    @Test
    fun launcherRendersAndRedactsAfterPrivacyChange(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as BloomApplication).repository
        val original = JSONObject(repo.read()).getJSONObject("profile")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("appwidget grantbind --package ${context.packageName}").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        val manager = AppWidgetManager.getInstance(context)
        lateinit var host: AppWidgetHost
        lateinit var widget: android.appwidget.AppWidgetHostView
        var id = 0
        try {
            fun profile(enabled: Boolean, discreet: Boolean) = runBlocking {
                repo.change(
                    command(
                        "profile",
                        JSONObject(original.toString())
                            .put("widgets_enabled", enabled)
                            .put("discreet", discreet),
                        JSONObject(repo.read()).getLong("revision"),
                    ),
                    true,
                )
            }
            val today = java.time.LocalDate.now().toString()
            val archive = JSONObject(repo.read())
            val day =
                archive.rows("days").firstOrNull { it.getString("day") == today }
                    ?: newRecord("day")
            repo.change(
                command(
                    "day",
                    day.put("flow", "light")
                        .put("period_start", true)
                        .put("notes", "widget-private-sentinel"),
                    archive.getLong("revision"),
                ),
                true,
            )
            profile(true, false)
            ui.runOnUiThread {
                host = AppWidgetHost(ui.activity, 171117)
                host.startListening()
                id = host.allocateAppWidgetId()
                assertTrue(
                    manager.bindAppWidgetIdIfAllowed(
                        id,
                        ComponentName(context, BloomWidget::class.java),
                    )
                )
                widget = host.createView(ui.activity, id, manager.getAppWidgetInfo(id))
                val density = context.resources.displayMetrics.density
                val root =
                    LinearLayout(ui.activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 100, 24, 24)
                        addView(
                            widget,
                            LinearLayout.LayoutParams(
                                (300 * density).toInt(),
                                (180 * density).toInt(),
                            ),
                        )
                    }
                ui.activity.setContentView(root)
                widget.updateAppWidgetSize(null, 300, 180, 300, 180)
            }
            BloomGlanceWidget.refresh(context)
            fun text(): String {
                var result = ""
                ui.runOnUiThread { result = texts(widget) }
                return result
            }
            ui.waitUntil(20000) { text().contains("Cycle day") }
            assertFalse(text().contains("widget-private-sentinel"))
            screenshot(context, "bloom-widget-visible")
            profile(true, true)
            BloomGlanceWidget.refresh(context)
            ui.waitUntil(20000) {
                !text().contains("Cycle day") &&
                    (text().contains("Open your journal") || text().contains("Next reminder"))
            }
            screenshot(context, "bloom-widget-discreet")
            profile(false, true)
            BloomGlanceWidget.refresh(context)
            ui.waitUntil(20000) { text().contains("Your private journal") }
            assertFalse(text().contains("Next reminder"))
            screenshot(context, "bloom-widget-disabled")
        } finally {
            repo.change(
                command("profile", original, JSONObject(repo.read()).getLong("revision")),
                true,
            )
            ui.runOnUiThread {
                if (id != 0) {
                    host.deleteAppWidgetId(id)
                    host.stopListening()
                }
            }
        }
    }

    private fun texts(view: View): String =
        (if (view is TextView) view.text.toString() else "") +
            (if (view is ViewGroup)
                (0 until view.childCount).joinToString(" ") { texts(view.getChildAt(it)) }
            else "")

    private fun screenshot(context: Context, name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
