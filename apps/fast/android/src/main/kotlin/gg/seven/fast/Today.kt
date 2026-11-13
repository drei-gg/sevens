@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package gg.seven.fast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.seven.fast.core.*
import gg.seven.kit.*
import gg.seven.kit.SevenColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
internal fun TodayScreen(
    m: FastModel,
    a: FastArchive,
    now: Double,
    open: OpenEditor,
    onPage: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    val active = a.active
    val timer: @Composable ColumnScope.() -> Unit = {
        TimerHero(a, now)
        if (active != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimeCard("Started", active.start.millis()) { open("fast", active.id) }
                active.goalDate?.let { goal -> TimeCard("Goal", goal) { open("fast", active.id) } }
            }
            Button(
                onClick = { open("end", active.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text("End fast", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Button(
                onClick = { open("start", null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Start fast", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(
                onClick = { onPage("Plans") },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Outlined.CalendarMonth, null)
                Spacer(Modifier.width(8.dp))
                Text("Plans & saved timers")
            }
        }
        if (active?.intention?.isNotBlank() == true)
            Panel("Your intention") { Text(active.intention) }
    }
    val daily: @Composable ColumnScope.() -> Unit = {
        WaterTodayCard(m, a, open, onPage, onUnlock)
        Card(
            onClick = { open("journal", null) },
            modifier = Modifier.fillMaxWidth().testTag("today-check-in"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            ListItem(
                headlineContent = { Text("Check in", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Notice your day") },
                leadingContent = { Icon(Icons.Outlined.EditNote, null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
        TextButton(onClick = { open("metric", null) }) {
            Icon(Icons.Outlined.MonitorWeight, null)
            Spacer(Modifier.width(8.dp))
            Text("Log measurement")
        }
    }
    Page {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 600.dp && LocalDensity.current.fontScale < 1.5f) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        content = timer,
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        content = daily,
                    )
                }
            } else
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    timer()
                    daily()
                }
        }
    }
}

@Composable
private fun TimerHero(a: FastArchive, now: Double) {
    var remaining by rememberSaveable { mutableStateOf(false) }
    val active = a.active
    val last = a.latestCompleted
    val windowEnd = last?.end?.let { it.millis() + last.eatingMinutes * 60_000.0 }
    val eating =
        active == null && a.settings.showEatingWindow && windowEnd != null && now < windowEnd
    val target = active?.goalMinutes?.times(60_000.0)
    val elapsed = active?.duration(now) ?: 0.0
    val reached = target != null && elapsed >= target
    val progress =
        when {
                active != null -> if (target != null) elapsed / target else 1.0
                eating -> (now - last!!.end!!.millis()) / (last.eatingMinutes * 60_000.0)
                else -> 0.0
            }
            .toFloat()
            .coerceIn(0f, 1f)
    val label =
        when {
            active != null ->
                if (reached) "Goal reached"
                else if (remaining && target != null) "Remaining" else "Fasting"
            eating -> "Eating window"
            last != null -> "Last fast"
            else -> "At your own pace."
        }
    val value =
        when {
            active != null ->
                timerClock(
                    if (remaining && target != null && !reached) target - elapsed else elapsed
                )
            eating -> timerClock(windowEnd!! - now)
            last != null -> duration(last.duration(now))
            else -> null
        }
    val caption =
        when {
            active != null -> target?.let { "Goal · ${duration(it)}" } ?: "Open ended"
            eating -> "Ends ${stamp(windowEnd!!)}"
            last != null -> "Finished ${stamp(last.end!!)}"
            else -> "A little structure. Room for real life."
        }
    val hue = if (active != null) SevenColors.time else MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val markerFill = MaterialTheme.colorScheme.surface
    val words: @Composable () -> Unit = {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active == null && last == null)
                Icon(Icons.Outlined.WbTwilight, null, Modifier.size(48.dp), tint = SevenColors.time)
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            value?.let {
                Text(
                    it,
                    style =
                        if (active != null || eating)
                            MaterialTheme.typography.displayLarge.copy(
                                fontSize = 48.sp,
                                fontFeatureSettings = "tnum",
                            )
                        else
                            MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (LocalDensity.current.fontScale >= 1.5f) {
            words()
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = hue)
        } else
            Box(
                Modifier.size(300.dp)
                    .then(
                        if (active != null && target != null && !reached)
                            Modifier.clickable(
                                onClickLabel =
                                    if (remaining) "Show elapsed time" else "Show remaining time"
                            ) {
                                remaining = !remaining
                            }
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize().padding(10.dp).clearAndSetSemantics {}) {
                    drawArc(
                        track,
                        -90f,
                        360f,
                        false,
                        style = Stroke(16.dp.toPx(), cap = StrokeCap.Round),
                    )
                    if (progress > 0)
                        drawArc(
                            hue,
                            -90f,
                            progress * 360,
                            false,
                            style = Stroke(16.dp.toPx(), cap = StrokeCap.Round),
                        )
                    if (active != null || eating) {
                        val angle = (progress * 360 - 90) * PI / 180
                        val radius = size.minDimension / 2
                        val point =
                            Offset(
                                center.x + radius * cos(angle).toFloat(),
                                center.y + radius * sin(angle).toFloat(),
                            )
                        drawCircle(markerFill, 7.dp.toPx(), point)
                        drawCircle(hue, 7.dp.toPx(), point, style = Stroke(4.dp.toPx()))
                    }
                }
                words()
            }
        if (active != null && target != null && !reached)
            TextButton(onClick = { remaining = !remaining }) {
                Text(if (remaining) "Show elapsed time" else "Show remaining time")
            }
        if (reached)
            Text(
                "The timer keeps running until you end it.",
                style = MaterialTheme.typography.bodySmall,
            )
        if (eating)
            Text(
                "A reminder, not a deadline. Start your next fast when it suits you.",
                style = MaterialTheme.typography.bodySmall,
            )
    }
}

@Composable
internal fun PlansScreen(m: FastModel, a: FastArchive, open: OpenEditor) {
    Page {
        SevenGroup(footer = "Plans are reminders, not records. Nothing starts automatically.") {
            ActionRow("Plan a fast", leading = { Icon(Icons.Outlined.EventAvailable, null) }) {
                open("plan", null)
            }
        }
        SevenGroup(header = "Planned fasts") {
            if (a.plans.isEmpty())
                ValueRow("Room for a plan", supporting = "Pick a date and time. Change your mind whenever you need.")
            a.plans
                .sortedBy { it.start }
                .forEach { plan ->
                    ValueRow(
                        stamp(plan.start),
                        plan.goalMinutes?.let { duration(it * 60_000.0) } ?: "Open ended",
                        supporting = plan.intention.ifBlank { null },
                        chevron = true,
                        onClick = { open("plan", plan.id) },
                    )
                }
        }
        SevenGroup(header = "Your presets") {
            a.presets.forEach { preset ->
                ValueRow(
                    preset.name,
                    preset.goalMinutes?.let { duration(it * 60_000.0) } ?: "Open ended",
                    chevron = true,
                    onClick = { open("preset", preset.id) },
                )
            }
            ActionRow("Save a custom preset", leading = { Icon(Icons.Outlined.Add, null) }) {
                open("preset", null)
            }
        }
    }
}

internal fun timerClock(milliseconds: Double): String {
    val total = (milliseconds / 1000).toLong().coerceAtLeast(0)
    return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
}

@Composable
private fun TimeCard(label: String, date: Double, action: () -> Unit) {
    Card(
        onClick = action,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                date
                    .instant()
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("EEE · HH:mm")),
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Edit", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun WaterAmount(a: FastArchive, day: LocalDate, large: Boolean = false) {
    val total = History.water(a.metrics, day, ZoneId.systemDefault())
    val count =
        a.metrics.count {
            it.kind == MetricKind.WATER && History.onDay(it.date, day, ZoneId.systemDefault())
        }
    val goal = a.settings.water.goalMilliliters
    val unit = Units.label(MetricKind.WATER, a.settings)
    if (large) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WaterLevel(goal?.let { total / it }, Modifier.size(168.dp))
            Text(
                "${Units.display(total,MetricKind.WATER,a.settings).number(0)} $unit",
                style =
                    MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
            )
            Text(
                if (goal == null) "$count ${if(count==1) "drink" else "drinks"}"
                else "of ${Units.display(goal,MetricKind.WATER,a.settings).number(0)} $unit",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WaterLevel(goal?.let { total / it }, Modifier.size(64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${Units.display(total,MetricKind.WATER,a.settings).number(0)} $unit",
                    style =
                        MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                )
                Text(
                    if (goal == null) "$count ${if(count==1) "drink" else "drinks"}"
                    else "of ${Units.display(goal,MetricKind.WATER,a.settings).number(0)} $unit",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
}

@Composable
private fun WaterTodayCard(
    m: FastModel,
    a: FastArchive,
    open: OpenEditor,
    onPage: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    var undoId by rememberSaveable { mutableStateOf<java.util.UUID?>(null) }
    val busy by m.working.collectAsState()
    Panel {
        Row(
            Modifier.fillMaxWidth().clickable { onPage("Water") },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.WaterDrop, null, tint = SevenColors.water)
            Text("Water today", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("History", style = MaterialTheme.typography.bodySmall)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
        }
        WaterAmount(a, LocalDate.now())
        if (a.settings.water.goalMilliliters == null)
            TextButton(onClick = { open("waterSettings", null) }) {
                Icon(Icons.Outlined.TrackChanges, null)
                Spacer(Modifier.width(8.dp))
                Text("Set a daily goal")
            }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                enabled = !busy,
                onClick = {
                    if (!m.canCreate) onUnlock()
                    else {
                        val entry =
                            metricEntry(
                                date = nowMillis(),
                                kind = MetricKind.WATER,
                                value = a.settings.water.quickAddMilliliters,
                            )
                        m.mutate(
                            { it.copy(metrics = it.metrics + entry) },
                            onSaved = { undoId = entry.id },
                        )
                    }
                },
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${Units.display(a.settings.water.quickAddMilliliters,MetricKind.WATER,a.settings).number(0)} ${Units.label(MetricKind.WATER,a.settings)}"
                )
            }
            FilledTonalButton(onClick = { open("water", null) }) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(6.dp))
                Text("Other amount")
            }
        }
        if (undoId != null && a.metrics.any { it.id == undoId })
            TextButton(
                enabled = !busy,
                onClick = {
                    val id = undoId
                    m.mutate(
                        { it.copy(metrics = it.metrics.filterNot { e -> e.id == id }) },
                        onSaved = { undoId = null },
                    )
                },
            ) {
                Text("Undo last glass")
            }
        if (a.settings.water.remindersEnabled)
            TextButton(
                onClick = {
                    m.mutate({
                        it.copy(
                            settings =
                                it.settings.copy(
                                    water =
                                        it.settings.water.copy(
                                            remindersPaused = !it.settings.water.remindersPaused
                                        )
                                )
                        )
                    })
                }
            ) {
                Text(
                    if (a.settings.water.remindersPaused) "Resume water reminders"
                    else "Pause water reminders"
                )
            }
    }
}
