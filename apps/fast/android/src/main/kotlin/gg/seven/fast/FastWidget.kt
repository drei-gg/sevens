package gg.seven.fast

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import gg.seven.fast.core.*
import gg.seven.kit.SevenSchemes

/**
 * The home-screen timer, built with Jetpack Glance in the app's Material colours. Display only:
 * no journal or measurements. The running time is a system Chronometer, so it ticks without
 * waking the app; the widget redraws whenever the archive changes.
 */
class FastGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as FastApplication).repository
        repository.load()
        val archive = repository.state.value
        provideContent {
            GlanceTheme(colors = widgetColors) { Content(context, archive) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content(context: Context, archive: FastArchive?) {
        val active = archive?.active
        val now = nowMillis()
        Column(
            GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(20.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                context.getString(R.string.app_name),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            )
            Spacer(GlanceModifier.height(4.dp))
            if (active != null) {
                val clock = RemoteViews(context.packageName, R.layout.widget_clock)
                clock.setChronometer(R.id.widget_clock, SystemClock.elapsedRealtime() - active.duration(now).toLong(), null, true)
                clock.setTextColor(R.id.widget_clock, ink(context))
                AndroidRemoteViews(clock)
                Text(
                    active.goalDate?.let { "Goal · ${stamp(it)}" } ?: "Open ended",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            } else {
                Text(
                    "No fast running",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    "Open to start, view or edit your records",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }
        }
    }

    private fun ink(context: Context): Int {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return (if (night) Color(0xFFEEE9DF) else Color(0xFF161513)).toArgb()
    }

    companion object {
        /** The Sevens' fixed scheme, never wallpaper colour (polish guide §3.1). */
        private val widgetColors = ColorProviders(light = SevenSchemes.light, dark = SevenSchemes.dark)

        suspend fun refresh(context: Context) = FastGlanceWidget().updateAll(context)
    }
}

class FastWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FastGlanceWidget()
}
