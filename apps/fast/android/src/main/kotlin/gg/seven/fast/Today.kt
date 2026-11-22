@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
                TimeCard(stringResource(R.string.started), active.start.millis()) {
                    open("fast", active.id)
                }
                active.goalDate?.let { goal ->
                    TimeCard(stringResource(R.string.goal), goal) { open("fast", active.id) }
                }
            }
            PrimaryAction(stringResource(R.string.end_fast), null) { open("end", active.id) }
        } else {
            PrimaryAction(stringResource(R.string.start_fast), Icons.Filled.PlayArrow) {
                open("start", null)
            }
            TextButton(
                onClick = { onPage("Plans") },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Outlined.CalendarMonth, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.today_plans))
            }
        }
        if (active?.intention?.isNotBlank() == true)
            Panel(stringResource(R.string.today_intention)) { Text(active.intention) }
    }
    val res = LocalResources.current
    val daily: @Composable ColumnScope.() -> Unit = {
        WaterTodayCard(m, a, open, onPage, onUnlock)
        Section {
            item(
                res.getString(R.string.check_in),
                supporting = res.getString(R.string.today_check_in_detail),
                icon = Icons.Outlined.EditNote,
                onClick = { open("journal", null) },
            )
            item(
                res.getString(R.string.log_measurement),
                icon = Icons.Outlined.MonitorWeight,
                onClick = { open("metric", null) },
            )
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
        stringResource(
            when {
                active != null ->
                    if (reached) R.string.goal_reached
                    else if (remaining && target != null) R.string.timer_remaining
                    else R.string.timer_fasting
                eating -> R.string.timer_eating_window
                last != null -> R.string.timer_last_fast
                else -> R.string.timer_own_pace
            }
        )
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
            active != null ->
                target?.let { stringResource(R.string.timer_goal, duration(it)) }
                    ?: stringResource(R.string.open_ended)
            eating -> stringResource(R.string.timer_window_ends, stamp(windowEnd!!))
            last != null -> stringResource(R.string.timer_finished, stamp(last.end!!))
            else -> stringResource(R.string.timer_structure)
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
                label.uppercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
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
        val toggleLabel =
            stringResource(if (remaining) R.string.timer_show_elapsed else R.string.timer_show_remaining)
        if (LocalDensity.current.fontScale >= 1.5f) {
            words()
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = hue)
        } else
            Box(
                Modifier.size(300.dp)
                    .then(
                        if (active != null && target != null && !reached)
                            Modifier.clickable(onClickLabel = toggleLabel) {
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
            TextButton(onClick = { remaining = !remaining }) { Text(toggleLabel) }
        if (reached)
            Text(
                stringResource(R.string.timer_keeps_running),
                style = MaterialTheme.typography.bodySmall,
            )
        if (eating)
            Text(
                stringResource(R.string.timer_window_note),
                style = MaterialTheme.typography.bodySmall,
            )
    }
}

@Composable
internal fun PlansScreen(m: FastModel, a: FastArchive, open: OpenEditor) {
    val gate = LocalSevenGate.current
    val res = LocalResources.current
    var startPlan by rememberSaveable { mutableStateOf<java.util.UUID?>(null) }
    Page {
        Text(
            stringResource(R.string.plans_intro),
            Modifier.padding(horizontal = Space.m),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Section(title = stringResource(R.string.plans_planned)) {
            if (a.plans.isEmpty())
                item(
                    res.getString(R.string.plans_empty),
                    supporting = res.getString(R.string.plans_empty_detail),
                )
            a.plans
                .sortedBy { it.start }
                .forEach { plan ->
                    item(
                        res.stamp(plan.start),
                        res.goal(plan.goalMinutes),
                        supporting = plan.intention.ifBlank { null },
                        onClick = { open("plan", plan.id) },
                    )
                    if (a.active == null)
                        action(res.getString(R.string.plans_start_now), Icons.Outlined.PlayCircle) {
                            if (m.canCreate) startPlan = plan.id
                            else gate.offer(res.getString(R.string.plans_starting_feature))
                        }
                }
            action(res.getString(R.string.plan_a_fast), Icons.Outlined.EventAvailable) {
                open("plan", null)
            }
        }
        Section(title = stringResource(R.string.plans_presets)) {
            a.presets.forEach { preset ->
                item(
                    preset.name,
                    res.goal(preset.goalMinutes),
                    onClick = { open("preset", preset.id) },
                )
            }
            action(res.getString(R.string.plans_save_preset), Icons.Outlined.Add) {
                open("preset", null)
            }
        }
    }
    val plan = a.plans.find { it.id == startPlan }
    if (plan != null)
        AlertDialog(
            onDismissRequest = { startPlan = null },
            title = { Text(stringResource(R.string.plans_start_title)) },
            text = { Text(stringResource(R.string.plans_start_message)) },
            dismissButton = {
                TextButton(onClick = { startPlan = null }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        startPlan = null
                        val now = nowMillis()
                        val fast =
                            fastRecord(
                                start = now,
                                goalMinutes = plan.goalMinutes,
                                eatingMinutes = plan.eatingMinutes,
                                intention = plan.intention,
                            )
                        // Starting turns the plan into a record: the plan itself goes.
                        m.mutate({ archive ->
                            archive.save(fast, now).let { saved ->
                                saved.copy(plans = saved.plans.filterNot { it.id == plan.id })
                            }
                        })
                    }
                ) {
                    Text(stringResource(R.string.plans_start_confirm))
                }
            },
        )
}

/** The screen's one primary action: an Expressive large button whose shape morphs on press. */
@Composable
private fun PrimaryAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    val height = ButtonDefaults.LargeContainerHeight
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Spacer(Modifier.width(Space.s))
        }
        Text(label, style = ButtonDefaults.textStyleFor(height))
    }
}

internal fun timerClock(milliseconds: Double): String {
    val total = (milliseconds / 1000).toLong().coerceAtLeast(0)
    return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
}

@Composable
private fun TimeCard(label: String, date: Double, action: () -> Unit) {
    val moment = date.instant().atZone(ZoneId.systemDefault())
    val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    Card(
        onClick = action,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label.uppercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.time_card_moment,
                    moment.format(DateTimeFormatter.ofPattern("EEE")),
                    SevenFormat.time(moment, h24),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.edit), style = MaterialTheme.typography.bodySmall)
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
    val amount = amount(total, MetricKind.WATER, a.settings, 0)
    val detail =
        if (goal == null) pluralStringResource(R.plurals.drinks, count, count)
        else stringResource(R.string.water_of_goal, amount(goal, MetricKind.WATER, a.settings, 0))
    if (large) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WaterLevel(goal?.let { total / it }, Modifier.size(168.dp))
            Text(
                amount,
                style =
                    MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
            )
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WaterLevel(goal?.let { total / it }, Modifier.size(64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    amount,
                    style =
                        MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                )
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val context = LocalContext.current
    val res = LocalResources.current
    // Resuming asks again when Android notifications aren't allowed (as iOS does); a denial
    // leaves tracking as it is.
    val permission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) {
            if (!it) m.message.value = res.getString(R.string.notifications_off_reminders)
        }
    Panel {
        Row(
            Modifier.fillMaxWidth().clickable { onPage("Water") },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.WaterDrop, null, tint = SevenColors.water)
            Text(
                stringResource(R.string.water_today),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.water_history), style = MaterialTheme.typography.bodySmall)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
        }
        WaterAmount(a, LocalDate.now())
        if (a.settings.water.goalMilliliters == null)
            TextButton(onClick = { open("waterSettings", null) }) {
                Icon(Icons.Outlined.TrackChanges, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.water_set_goal))
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
                Text(amount(a.settings.water.quickAddMilliliters, MetricKind.WATER, a.settings, 0))
            }
            FilledTonalButton(onClick = { open("water", null) }) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.water_other_amount))
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
                Text(stringResource(R.string.water_undo_glass))
            }
        if (a.settings.water.remindersEnabled)
            TextButton(
                enabled = !busy,
                onClick = {
                    val resuming = a.settings.water.remindersPaused
                    m.mutate(
                        {
                            it.copy(
                                settings =
                                    it.settings.copy(
                                        water = it.settings.water.copy(remindersPaused = !resuming)
                                    )
                            )
                        },
                        onSaved = {
                            if (
                                resuming &&
                                    android.os.Build.VERSION.SDK_INT >= 33 &&
                                    context.checkSelfPermission(
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            )
                                permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )
                },
            ) {
                Text(
                    stringResource(
                        if (a.settings.water.remindersPaused) R.string.water_resume_reminders
                        else R.string.water_pause_reminders
                    )
                )
            }
    }
}
