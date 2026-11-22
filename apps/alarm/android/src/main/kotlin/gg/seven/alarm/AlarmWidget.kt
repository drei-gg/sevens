package gg.seven.alarm

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
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
import gg.seven.alarm.core.*
import gg.seven.kit.SevenSchemes
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only the minimal projection is readable by the widget; never export the archive or missions. */
object AlarmWidgetStore {
    private val lock = Mutex()
    private val current = MutableStateFlow<AlarmWidgetSnapshot?>(null)
    val state = current.asStateFlow()

    private fun file(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, "alarm-widget.json"))

    suspend fun write(context: Context, snapshot: AlarmWidgetSnapshot?) =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val file = file(context)
                if (snapshot == null) {
                    file.delete()
                    current.value = null
                    return@withContext
                }
                val bytes = alarmWidgetEncode(snapshot).toByteArray(Charsets.UTF_8)
                val stream = file.startWrite()
                try {
                    stream.write(bytes)
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
                current.value = snapshot
            }
        }

    suspend fun read(context: Context): AlarmWidgetSnapshot? =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val snapshot =
                    runCatching {
                            file(context).openRead().use { stream ->
                                val bytes = ByteArray(65_537)
                                var count = 0
                                while (count < bytes.size) {
                                    val read = stream.read(bytes, count, bytes.size - count)
                                    if (read < 0) break
                                    count += read
                                }
                                require(count <= 65_536)
                                alarmWidgetDecode(String(bytes, 0, count, Charsets.UTF_8))
                            }
                        }
                        .getOrNull()
                current.value = snapshot
                snapshot
            }
        }
}

/**
 * Saved schedule, not a delivery monitor. Tapping opens the native editor; never arms from here.
 */
class AlarmGlanceWidget : GlanceAppWidget() {
    override val sizeMode =
        SizeMode.Responsive(setOf(DpSize(240.dp, 200.dp), DpSize(300.dp, 320.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AlarmWidgetStore.read(context)
        provideContent {
            val snapshot by AlarmWidgetStore.state.collectAsState()
            val refreshed = currentState<Preferences>()[refreshKey] ?: 0L
            GlanceTheme(
                colors = ColorProviders(light = SevenSchemes.light, dark = SevenSchemes.dark)
            ) {
                key(refreshed) { Content(context, snapshot) }
            }
        }
    }

    @Composable
    private fun Content(context: Context, snapshot: AlarmWidgetSnapshot?) {
        val zone = ZoneId.systemDefault()
        val view = snapshot?.let { alarmWidgetView(it, Instant.now(), zone.id) }
        val checkedTime =
            snapshot
                ?.updated
                ?.let {
                    android.text.format.DateFormat.getTimeFormat(context)
                        .format(java.util.Date.from(it))
                }
                .orEmpty()
        val largeText = context.resources.configuration.fontScale >= 1.5f
        val count = if (!largeText && LocalSize.current.height >= 320.dp) 3 else 1
        Column(
            GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .appWidgetBackground()
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            if (!largeText)
                Text(
                    "Saved schedule",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            Spacer(GlanceModifier.height(8.dp))
            if (view == null || view.stale) {
                Text(
                    "Open to refresh",
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            } else {
                if (view.items.isEmpty()) {
                    Text(
                        "No upcoming times",
                        style =
                            TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }
                // Glance rows/columns accept at most ten direct children. Keep each schedule
                // entry grouped so expanded widgets cannot silently drop the later entries.
                view.items.take(count).forEach { item ->
                    Column {
                        val time =
                            android.text.format.DateFormat.getTimeFormat(context)
                                .format(java.util.Date.from(item.date))
                        val day =
                            item.date
                                .atZone(zone)
                                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                        Text(
                            time,
                            style =
                                TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize =
                                        if (largeText) 24.sp else if (count == 1) 28.sp else 22.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                            maxLines = 1,
                        )
                        Text(
                            item.name,
                            style =
                                TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                            maxLines = 1,
                        )
                        Text(
                            day,
                            style =
                                TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontSize = 12.sp,
                                ),
                            maxLines = 1,
                        )
                        if (!largeText)
                            Text(
                                when (item.purpose) {
                                    "snooze" -> "Snoozed alarm"
                                    "followUp" -> "Wake-up check"
                                    else ->
                                        if (item.delivery == Delivery.ALARM) "Alarm"
                                        else "Quiet reminder"
                                },
                                style =
                                    TextStyle(
                                        color = GlanceTheme.colors.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    ),
                                maxLines = 1,
                            )
                        Spacer(GlanceModifier.height(8.dp))
                    }
                }
                Text(
                    if (view.needsAttention)
                        (if (largeText) "Check in app" else "Open to check scheduling")
                    else "Checked $checkedTime",
                    style =
                        TextStyle(
                            color =
                                if (view.needsAttention) GlanceTheme.colors.error
                                else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp,
                        ),
                    maxLines = 2,
                )
            }
        }
    }

    companion object {
        private val refreshKey = longPreferencesKey("refresh")

        suspend fun refresh(context: Context) {
            try {
                val widget = AlarmGlanceWidget()
                for (id in
                    GlanceAppWidgetManager(context).getGlanceIds(AlarmGlanceWidget::class.java)) {
                    updateAppWidgetState(context, id) {
                        it[refreshKey] = (it[refreshKey] ?: 0L) + 1L
                    }
                    widget.update(context, id)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                android.util.Log.w("AlarmWidget", "Launcher refresh deferred")
            }
        }
    }
}

class AlarmWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AlarmGlanceWidget()
}
