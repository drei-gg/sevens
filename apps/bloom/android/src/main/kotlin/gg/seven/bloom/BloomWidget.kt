package gg.seven.bloom

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import gg.seven.bloom.core.bloomDashboard
import gg.seven.kit.SevenSchemes
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** A privacy projection of the Rust dashboard. Never persist an unencrypted journal snapshot. */
internal data class BloomWidgetSnapshot(
    val enabled: Boolean,
    val cycleDay: Int?,
    val nextReminder: Long?,
) {
    companion object {
        fun from(archive: JSONObject, dashboard: JSONObject, now: Long): BloomWidgetSnapshot {
            val profile = archive.getJSONObject("profile")
            val enabled = profile.getBoolean("widgets_enabled")
            return BloomWidgetSnapshot(
                enabled,
                if (enabled && !profile.getBoolean("discreet") && !dashboard.isNull("cycle_day"))
                    dashboard.getInt("cycle_day")
                else null,
                if (enabled)
                    dashboard
                        .rows("reminders")
                        .map { it.getLong("at") }
                        .filter { it > now }
                        .minOrNull()
                else null,
            )
        }
    }
}

class BloomGlanceWidget : GlanceAppWidget() {
    override val sizeMode =
        SizeMode.Responsive(setOf(DpSize(150.dp, 140.dp), DpSize(260.dp, 140.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val refreshed = currentState<Preferences>()[refreshKey] ?: 0L
            val snapshot by
                produceState(BloomWidgetSnapshot(false, null, null), refreshed) {
                    // update() reuses a live Glance session. Read again when the persisted
                    // generation
                    // changes, and redact while loading instead of retaining a previously visible
                    // day.
                    value = BloomWidgetSnapshot(false, null, null)
                    value =
                        try {
                            val repository =
                                (context.applicationContext as BloomApplication).repository
                            val archive = repository.read()
                            val now = Instant.now().epochSecond
                            BloomWidgetSnapshot.from(
                                JSONObject(archive),
                                JSONObject(bloomDashboard(archive, now, ZoneId.systemDefault().id)),
                                now,
                            )
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            BloomWidgetSnapshot(false, null, null)
                        }
                }
            GlanceTheme(
                colors = ColorProviders(light = SevenSchemes.light, dark = SevenSchemes.dark)
            ) {
                Content(context, snapshot)
            }
        }
    }

    @Composable
    private fun Content(context: Context, snapshot: BloomWidgetSnapshot) {
        Column(
            GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .appWidgetBackground()
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                context.getString(R.string.app_name),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(8.dp))
            if (!snapshot.enabled) {
                Text(
                    "Your private journal",
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
                Text(
                    "Open the app",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            } else {
                snapshot.cycleDay?.let {
                    Text(
                        "Cycle day $it",
                        style =
                            TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }
                snapshot.nextReminder?.let { at ->
                    Text(
                        "Next reminder",
                        style =
                            TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    )
                    val date = java.util.Date(at * 1000)
                    val day = Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault()).toLocalDate()
                    val time = android.text.format.DateFormat.getTimeFormat(context).format(date)
                    val shown =
                        if (day == java.time.LocalDate.now()) time
                        else
                            "${android.text.format.DateFormat.getMediumDateFormat(context).format(date)} · $time"
                    Text(
                        shown,
                        style =
                            TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }
                    ?: Text(
                        "Open your journal",
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp),
                    )
            }
        }
    }

    companion object {
        private val refreshKey = longPreferencesKey("refresh")

        suspend fun refresh(context: Context) {
            try {
                val widget = BloomGlanceWidget()
                for (id in
                    GlanceAppWidgetManager(context).getGlanceIds(BloomGlanceWidget::class.java)) {
                    updateAppWidgetState(context, id) {
                        it[refreshKey] = (it[refreshKey] ?: 0L) + 1
                    }
                    widget.update(context, id)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                android.util.Log.w(
                    "BloomWidget",
                    "Widget refresh deferred until the next launcher update",
                )
            }
        }
    }
}

class BloomWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BloomGlanceWidget()
}
