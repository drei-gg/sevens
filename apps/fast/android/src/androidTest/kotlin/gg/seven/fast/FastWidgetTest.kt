package gg.seven.fast

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.fast.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FastWidgetTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun widgetTicksAndRefreshesAfterFastEnds(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as FastApplication
        app.repository.load()
        val original = app.repository.state.value!!
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("appwidget grantbind --package ${context.packageName}").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        val manager = AppWidgetManager.getInstance(context)
        lateinit var host: AppWidgetHost
        lateinit var widget: android.appwidget.AppWidgetHostView
        var id = 0
        try {
            app.repository.update {
                FastArchive.empty()
                    .copy(fasts = listOf(fastRecord(start = nowMillis() - 3_600_000)))
            }
            ui.runOnUiThread {
                host = AppWidgetHost(ui.activity, 171118)
                host.startListening()
                id = host.allocateAppWidgetId()
                assertTrue(
                    manager.bindAppWidgetIdIfAllowed(
                        id,
                        ComponentName(context, FastWidget::class.java),
                    )
                )
                widget = host.createView(ui.activity, id, manager.getAppWidgetInfo(id))
                val density = context.resources.displayMetrics.density
                val root =
                    LinearLayout(ui.activity).apply {
                        setPadding(24, 100, 24, 24)
                        addView(
                            widget,
                            LinearLayout.LayoutParams(
                                (170 * density).toInt(),
                                (180 * density).toInt(),
                            ),
                        )
                    }
                ui.activity.setContentView(root)
                widget.updateAppWidgetSize(null, 170, 180, 170, 180)
            }
            FastGlanceWidget.refresh(context)
            fun text(): String {
                var result = ""
                ui.runOnUiThread { result = texts(widget) }
                return result
            }
            ui.waitUntil(20000) { text().contains("1:00:") }
            val first = text()
            ui.waitUntil(5000) { text() != first }
            screenshot(context, "fast-widget-running")
            app.repository.update { FastArchive.empty() }
            FastGlanceWidget.refresh(context)
            ui.waitUntil(20000) { text().contains("No fast running") }
            screenshot(context, "fast-widget-idle")
        } finally {
            app.repository.update { original }
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

    private fun screenshot(context: android.content.Context, name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
