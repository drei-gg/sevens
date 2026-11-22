package gg.seven.fast

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import gg.seven.fast.core.*
import gg.seven.kit.SevenSchemes

/**
 * The home-screen timer, built with Jetpack Glance in the app's Material colours. Display only: no
 * journal or measurements. The running time is a system Chronometer, so it ticks without waking the
 * app; the widget redraws whenever the archive changes.
 */
class FastGlanceWidget : GlanceAppWidget() {
    override val sizeMode =
        SizeMode.Responsive(setOf(DpSize(150.dp, 140.dp), DpSize(260.dp, 140.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as FastApplication).repository
        repository.load()
        repository.state.value?.let { WidgetGoalRefresh.schedule(context, it) }
        provideContent {
            val archive by repository.state.collectAsState()
            val failure by repository.failure.collectAsState()
            val refreshed = currentState<Preferences>()[refreshKey] ?: 0L
            GlanceTheme(colors = widgetColors) {
                key(refreshed) { Content(context, archive, failure != null) }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content(context: Context, archive: FastArchive?, failed: Boolean) {
        val active = archive?.active
        val now = nowMillis()
        Column(
            GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .appWidgetBackground()
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Text(
                context.getString(R.string.app_name),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            )
            Spacer(GlanceModifier.height(4.dp))
            if (failed) {
                Text(
                    context.getString(R.string.widget_review_records),
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            } else if (active != null) {
                val clock = RemoteViews(context.packageName, R.layout.widget_clock)
                clock.setChronometer(
                    R.id.widget_clock,
                    SystemClock.elapsedRealtime() - active.duration(now).toLong(),
                    null,
                    true,
                )
                clock.setTextColor(R.id.widget_clock, ink(context))
                clock.setTextViewTextSize(
                    R.id.widget_clock,
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    if (LocalSize.current.width < 220.dp) 26f else 34f,
                )
                AndroidRemoteViews(clock, modifier = GlanceModifier.fillMaxWidth().height(48.dp))
                Text(
                    active.goalDate?.let {
                        if (it.toLong() <= now) context.getString(R.string.goal_reached)
                        else
                            context.getString(
                                R.string.widget_planned_finish,
                                android.text.format.DateFormat.getTimeFormat(context)
                                    .format(java.util.Date(it.toLong())),
                            )
                    } ?: context.getString(R.string.open_ended),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            } else {
                Text(
                    context.getString(R.string.widget_placeholder),
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
                Text(
                    context.getString(R.string.widget_open_to_start),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }
        }
    }

    private fun ink(context: Context): Int {
        val night =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return (if (night) Color(0xFFEEE9DF) else Color(0xFF161513)).toArgb()
    }

    companion object {
        /** The Sevens' fixed scheme, never wallpaper colour (polish guide §3.1). */
        private val widgetColors =
            ColorProviders(light = SevenSchemes.light, dark = SevenSchemes.dark)

        private val refreshKey = longPreferencesKey("refresh")

        suspend fun refresh(context: Context) {
            try {
                val widget = FastGlanceWidget()
                for (id in
                    GlanceAppWidgetManager(context).getGlanceIds(FastGlanceWidget::class.java)) {
                    updateAppWidgetState(context, id) {
                        it[refreshKey] = (it[refreshKey] ?: 0L) + 1
                    }
                    widget.update(context, id)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                android.util.Log.w(
                    "FastWidget",
                    "Widget refresh deferred until the next launcher update",
                )
            }
        }
    }
}

class FastWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FastGlanceWidget()
}
