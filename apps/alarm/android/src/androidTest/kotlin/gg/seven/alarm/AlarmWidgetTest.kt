package gg.seven.alarm

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.alarm.core.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AlarmWidgetTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app: AlarmApplication = ApplicationProvider.getApplicationContext()

    @Test
    fun launcherWidgetShowsPersistedScheduleFailuresRetryAndRemoval() = runBlocking {
        val expanded = InstrumentationRegistry.getArguments().getString("widgetExpanded") == "true"
        val width = if (expanded) 360 else 260
        val height = if (expanded) 380 else 220
        val driver = PreviewAlarmDriver()
        val original = AlarmWidgetStore.read(app)
        val engine =
            AlarmEngine(
                ArchiveRepository(null),
                driver,
                isPreview = true,
                widgetSink = { AlarmWidgetStore.write(app, it) },
            )
        app.engine = engine
        app.getSharedPreferences("seven-launch", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("beforeYouBuy", true)
            .commit()
        app.getSharedPreferences("seven-trial", Context.MODE_PRIVATE)
            .edit()
            .putLong("start", System.currentTimeMillis())
            .putLong("lastSeen", System.currentTimeMillis())
            .commit()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) {
            automation.executeShellCommand(command).use {
                java.io.FileInputStream(it.fileDescriptor).readBytes()
            }
        }
        shell("appwidget grantbind --package ${app.packageName}")
        val scenario = ActivityScenario.launch(WidgetHostActivity::class.java)
        lateinit var host: AppWidgetHost
        lateinit var widget: android.appwidget.AppWidgetHostView
        var id = 0
        try {
            engine.reload()
            engine.synchronize()
            scenario.onActivity { activity ->
                host = AppWidgetHost(activity, 181118)
                host.startListening()
                id = host.allocateAppWidgetId()
                val manager = AppWidgetManager.getInstance(app)
                assertTrue(
                    manager.bindAppWidgetIdIfAllowed(
                        id,
                        ComponentName(app, AlarmWidget::class.java),
                    )
                )
                widget = host.createView(activity, id, manager.getAppWidgetInfo(id))
                val density = activity.resources.displayMetrics.density
                val root =
                    LinearLayout(activity).apply {
                        setBackgroundColor(
                            if (
                                (activity.resources.configuration.uiMode and
                                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                                    android.content.res.Configuration.UI_MODE_NIGHT_YES)
                            )
                                android.graphics.Color.DKGRAY
                            else android.graphics.Color.WHITE
                        )
                        setPadding(16, 110, 16, 16)
                        addView(
                            widget,
                            LinearLayout.LayoutParams(
                                (width * density).toInt(),
                                (height * density).toInt(),
                            ),
                        )
                    }
                activity.setContentView(root)
                widget.updateAppWidgetSize(null, width, height, width, height)
            }
            fun text(): String {
                var value = ""
                scenario.onActivity { value = texts(widget) }
                return value
            }
            suspend fun refresh() {
                AlarmGlanceWidget.refresh(app)
            }
            refresh()
            ui.waitUntil(20_000) { text().contains("No upcoming times") }
            val routine =
                newRoutine(RoutineKind.ALARM, Instant.now())
                    .copy(
                        name = "Widget study",
                        timing = Timing.ONCE,
                        once = Instant.now().plusSeconds(3600),
                    )
            driver.failure = "test scheduling failure"
            assertTrue(engine.save(routine, true))
            refresh()
            ui.waitUntil(20_000) {
                text().contains("Widget study") &&
                    (text().contains("check scheduling") || text().contains("Check in app"))
            }
            assertTrue(AlarmWidgetStore.read(app)!!.needsAttention)
            capture("alarm-widget-unverified")
            driver.failure = null
            engine.synchronize()
            refresh()
            ui.waitUntil(20_000) { text().contains("Checked ") }
            assertTrue(AlarmWidgetStore.read(app)!!.items.first().registered)
            if (expanded) {
                for ((index, name) in listOf("Stretch", "Walk").withIndex()) {
                    val loop =
                        newRoutine(RoutineKind.LOOP, Instant.now())
                            .copy(
                                name = name,
                                timing = Timing.ONCE,
                                once = Instant.now().plusSeconds((index + 2L) * 3600),
                            )
                    assertTrue(engine.save(loop, true))
                }
                refresh()
                ui.waitUntil(20_000) { text().contains("Stretch") && text().contains("Walk") }
            }
            // A failed archive write must not replace the last snapshot with unsaved input.
            val before = AlarmWidgetStore.read(app)
            assertFalse(engine.mutate { it.copy(version = 999) })
            assertEquals(before, AlarmWidgetStore.read(app))
            scenario.onActivity {
                com.google.android.apps.common.testing.accessibility.framework.integrations.espresso
                    .AccessibilityValidator()
                    .setRunChecksFromRootView(false)
                    .check(widget)
                fun checkBounds(view: View) {
                    if (view is TextView && view.text.isNotEmpty()) {
                        val bounds = android.graphics.Rect()
                        assertTrue(
                            "Widget text must be visible: ${view.text}",
                            view.getGlobalVisibleRect(bounds),
                        )
                        assertEquals(
                            "Widget text must not be vertically clipped: ${view.text}",
                            view.height,
                            bounds.height(),
                        )
                        assertTrue(
                            "Widget text layout must fit: ${view.text}",
                            view.layout?.let { layout ->
                                (0 until layout.lineCount).all { line ->
                                    layout.getEllipsisCount(line) == 0
                                }
                            } ?: false,
                        )
                    }
                    if (view is ViewGroup)
                        for (n in 0 until view.childCount) checkBounds(view.getChildAt(n))
                }
                checkBounds(widget)
            }
            capture("alarm-widget-scheduled")
            for (saved in engine.archive.value.routines) engine.toggle(saved, false, false)
            refresh()
            ui.waitUntil(20_000) { text().contains("No upcoming times") }
            assertTrue(driver.rows.isEmpty())
            val staleNow = Instant.now()
            val stale =
                before!!.copy(
                    updated = staleNow.minusSeconds(90_000),
                    validUntil = staleNow.minusSeconds(3600),
                )
            AlarmWidgetStore.write(app, stale)
            refresh()
            ui.waitUntil(20_000) { text().contains("Open to refresh") }
            assertFalse(text().contains("Widget study"))
            capture("alarm-widget-stale")
            AlarmWidgetStore.write(app, null)
            refresh()
            ui.waitUntil(20_000) { text().contains("Open to refresh") }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                fun clickable(view: View): View? {
                    if (view.isClickable) return view
                    if (view is ViewGroup)
                        for (n in 0 until view.childCount) clickable(view.getChildAt(n))?.let {
                            return it
                        }
                    return null
                }
                scenario.onActivity { assertTrue(requireNotNull(clickable(widget)).performClick()) }
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                assertNotNull("Widget must open the real app", opened)
                ui.onNodeWithTag("tab-Alarms").assertIsDisplayed()
                instrumentation.runOnMainSync { opened?.finish() }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        } finally {
            scenario.onActivity {
                if (id != 0) {
                    host.deleteAppWidgetId(id)
                    host.stopListening()
                }
            }
            scenario.close()
            AlarmWidgetStore.write(app, original)
            shell("appwidget revokebind --package ${app.packageName}")
        }
    }

    private fun texts(view: View): String =
        (if (view is TextView) view.text.toString() else "") +
            (if (view is ViewGroup)
                (0 until view.childCount).joinToString(" ") { texts(view.getChildAt(it)) }
            else "")

    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
