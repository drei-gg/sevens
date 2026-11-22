@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package gg.seven.fast

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import gg.seven.kit.*
import java.time.*
import kotlin.math.roundToInt

/** A date (and time) field outside a form. */
@Composable
internal fun NativeDateTime(
    label: String,
    value: Double,
    withTime: Boolean = true,
    onChange: (Double) -> Unit,
) {
    DateTimeField(label, value, withTime = withTime, onChange = onChange)
}

/** A switch with its label; the whole line toggles. */
@Composable
internal fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // Aligned with the fields around it: a form line, not an inset list row.
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A form value: no trailing ".0", "." as the separator so parsing stays locale-proof. */
internal fun plain(value: Double): String =
    java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

internal fun String.decimal(): Double? = trim().replace(',', '.').toDoubleOrNull()

@Composable
internal fun EditorDialog(
    m: FastModel,
    a: FastArchive,
    type: String,
    id: java.util.UUID?,
    initialDate: Double? = null,
    initialFastId: java.util.UUID? = null,
    dismiss: () -> Unit,
) {
    val busy by m.working.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val f = a.fasts.find { it.id == id }
    val j = a.journal.find { it.id == id }
    val metric = a.metrics.find { it.id == id }
    val plan = a.plans.find { it.id == id }
    val rest = a.restDays.find { it.id == id }
    val preset = a.presets.find { it.id == id }
    val baseNow = remember { initialDate ?: nowMillis() }
    var start by rememberSaveable {
        mutableDoubleStateOf(
            (f?.start ?: j?.date ?: metric?.date ?: plan?.start ?: rest?.date)?.millis()
                ?: if (type == "past") baseNow - 16 * 3_600_000
                else if (type == "plan") baseNow + 86_400_000 else baseNow
        )
    }
    var end by rememberSaveable { mutableDoubleStateOf(f?.end?.millis() ?: baseNow) }
    var openEnded by rememberSaveable {
        mutableStateOf(
            if (f != null) f.goalMinutes == null
            else if (plan != null) plan.goalMinutes == null
            else if (preset != null) preset.goalMinutes == null else false
        )
    }
    var goal by rememberSaveable {
        mutableStateOf(
            plain((f?.goalMinutes ?: plan?.goalMinutes ?: preset?.goalMinutes ?: 960) / 60.0)
        )
    }
    var eating by rememberSaveable {
        mutableStateOf(
            plain((f?.eatingMinutes ?: plan?.eatingMinutes ?: preset?.eatingMinutes ?: 480) / 60.0)
        )
    }
    var intention by rememberSaveable { mutableStateOf(f?.intention ?: plan?.intention ?: "") }
    var reflection by rememberSaveable { mutableStateOf(f?.reflection ?: "") }
    var text by rememberSaveable { mutableStateOf(j?.text ?: rest?.note ?: preset?.name ?: "") }
    var tags by rememberSaveable {
        mutableStateOf((f?.tags ?: j?.tags).orEmpty().joinToString(", "))
    }
    var linkedFast by rememberSaveable {
        mutableStateOf((if (j == null) initialFastId ?: a.active?.id else j.fastId)?.toString())
    }
    var mood by rememberSaveable { mutableStateOf(j?.mood?.toString().orEmpty()) }
    var energy by rememberSaveable { mutableStateOf(j?.energy?.toString().orEmpty()) }
    var hunger by rememberSaveable { mutableStateOf(j?.hunger?.toString().orEmpty()) }
    var kindText by rememberSaveable {
        mutableStateOf(metric?.kind?.key ?: if (type == "water") "water" else "weight")
    }
    val kind = metricKind(kindText)
    var value by rememberSaveable {
        mutableStateOf(
            metric?.let { plain(Units.display(it.value, it.kind, a.settings)) }
                ?: if (type == "water")
                    plain(
                        Units.display(
                            a.settings.water.quickAddMilliliters,
                            MetricKind.WATER,
                            a.settings,
                        )
                    )
                else ""
        )
    }
    var delete by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val title =
        stringResource(
            when (type) {
                "start" -> R.string.editor_start
                "past" -> R.string.add_past_fast
                "end" -> R.string.end_fast
                "fast" -> R.string.edit_fast
                "journal" -> if (j == null) R.string.check_in else R.string.editor_journal
                "water" -> R.string.editor_water
                "metric" -> R.string.editor_metric
                "plan" -> R.string.plan_a_fast
                "rest" -> R.string.rest_day
                "preset" -> R.string.editor_preset
                else -> R.string.water_settings
            }
        )
    if (type == "waterSettings") {
        WaterSettingsDialog(m, a, dismiss)
        return
    }
    fun save() {
        try {
            fun integer(s: String) = s.toIntOrNull() ?: error(res.getString(R.string.error_rating))
            val parsedTags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val newGoal =
                if (type in listOf("start", "past", "end", "fast", "plan", "preset")) {
                    if (openEnded) null
                    else ((goal.decimal() ?: error(res.getString(R.string.error_goal_hours))) * 60).roundToInt()
                } else null
            val eat =
                if (type in listOf("start", "past", "end", "fast", "plan", "preset"))
                    ((eating.decimal() ?: error(res.getString(R.string.error_eating_hours))) * 60)
                        .roundToInt()
                else 480
            val newMetric =
                if (type in listOf("metric", "water"))
                    Units.canonical(
                        value.decimal() ?: error(res.getString(R.string.error_amount)),
                        kind,
                        a.settings,
                    )
                else 0.0
            fun rating(s: String) = if (s.isBlank()) null else integer(s)
            val newMood = rating(mood)
            val newEnergy = rating(energy)
            val newHunger = rating(hunger)
            m.mutate(
                { current ->
                    when (type) {
                        "start",
                        "past",
                        "end",
                        "fast" ->
                            current.save(
                                fastRecord(
                                    id = f?.id ?: newId(),
                                    start = start,
                                    end =
                                        if (type == "past" || type == "end" || f?.end != null) end
                                        else null,
                                    goalMinutes = newGoal,
                                    eatingMinutes = eat,
                                    timeZoneId = f?.timeZoneId ?: ZoneId.systemDefault().id,
                                    intention = intention,
                                    reflection = reflection,
                                    tags = parsedTags,
                                ),
                                nowMillis(),
                            )
                        "journal" -> {
                            val entry =
                                JournalEntry(
                                    j?.id ?: newId(),
                                    start.instant(),
                                    linkedFast?.let(java.util.UUID::fromString),
                                    text,
                                    newMood,
                                    newEnergy,
                                    newHunger,
                                    parsedTags,
                                )
                            current.copy(
                                journal = current.journal.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "metric",
                        "water" -> {
                            val entry =
                                MetricEntry(
                                    metric?.id ?: newId(),
                                    start.instant(),
                                    kind,
                                    newMetric,
                                    metric?.externalId,
                                )
                            current.copy(
                                metrics = current.metrics.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "plan" -> {
                            val entry =
                                PlannedFast(
                                    plan?.id ?: newId(),
                                    start.instant(),
                                    newGoal,
                                    eat,
                                    intention,
                                )
                            current.copy(
                                plans = current.plans.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "rest" -> {
                            val entry = RestDay(rest?.id ?: newId(), start.instant(), text)
                            current.copy(
                                restDays = current.restDays.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "preset" -> {
                            val entry = FastPreset(preset?.id ?: newId(), text, newGoal, eat)
                            current.copy(
                                presets = current.presets.filterNot { it.id == entry.id } + entry
                            )
                        }
                        else -> current
                    }
                },
                dismiss,
            )
        } catch (e: Exception) {
            localError = res.explain(e)
        }
    }
    val draft =
        listOf(
                start,
                end,
                openEnded,
                goal,
                eating,
                intention,
                reflection,
                text,
                tags,
                linkedFast,
                mood,
                energy,
                hunger,
                kindText,
                value,
            )
            .joinToString("\u0000")
    val pristine = rememberSaveable { draft }
    val fastLike = type in listOf("start", "past", "end", "fast", "plan", "preset")
    val hasEnd = type == "past" || type == "end" || f?.end != null
    val goalMinutes = if (openEnded) null else goal.decimal()?.times(60)?.roundToInt()
    val eatingMinutes = eating.decimal()?.times(60)?.roundToInt()
    FullScreenForm(
        title,
        { if (!busy) if (draft == pristine) dismiss() else discard = true },
        ::save,
        busy = busy,
    ) {
        if (type in listOf("start", "past", "plan")) {
            FormHeading(stringResource(R.string.editor_rhythm))
            val presets = FastPreset.defaults + a.presets
            ChoiceChips(
                presets.map { it to res.presetName(it) },
                presets.firstOrNull {
                    it.goalMinutes == goalMinutes && it.eatingMinutes == eatingMinutes
                },
            ) { p ->
                openEnded = p.goalMinutes == null
                goal = plain((p.goalMinutes ?: 960) / 60.0)
                eating = plain(p.eatingMinutes / 60.0)
            }
        }
        if (type == "preset") TextInput(stringResource(R.string.editor_preset_name), text) { text = it }
        if (type != "preset") {
            DateTimeField(
                stringResource(
                    when {
                        type == "plan" -> R.string.editor_planned_start
                        fastLike -> R.string.started
                        type == "rest" -> R.string.day
                        else -> R.string.editor_when
                    }
                ),
                start,
                withTime = type != "rest",
            ) {
                start = it
            }
            if (hasEnd) {
                DateTimeField(stringResource(R.string.finished), end) { end = it }
                Text(
                    stringResource(R.string.editor_duration, duration(end - start)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (fastLike) {
            SwitchLine(stringResource(R.string.editor_set_goal), !openEnded) { openEnded = !it }
            val hr = stringResource(R.string.unit_hours_short)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (!openEnded)
                    NumberField(stringResource(R.string.goal), goal, Modifier.weight(1f), suffix = hr) {
                        goal = it
                    }
                NumberField(
                    stringResource(R.string.eating_window),
                    eating,
                    Modifier.weight(1f),
                    suffix = hr,
                ) {
                    eating = it
                }
            }
            Text(
                stringResource(R.string.editor_limits_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fastLike && type != "preset")
            TextInput(
                stringResource(R.string.intention),
                intention,
                minLines = 2,
                supporting = stringResource(R.string.editor_intention_prompt),
            ) {
                intention = it
            }
        if (hasEnd)
            TextInput(
                stringResource(R.string.reflection),
                reflection,
                minLines = 2,
                supporting = stringResource(R.string.editor_reflection_prompt),
            ) {
                reflection = it
            }
        if (type == "rest") TextInput(stringResource(R.string.editor_note), text) { text = it }
        if (type == "journal") {
            DropdownField(
                stringResource(R.string.editor_linked_fast),
                linkedFast.orEmpty(),
                listOf("" to stringResource(R.string.editor_ordinary_day)) +
                    a.fasts
                        .sortedByDescending { it.start }
                        .map { it.id.toString() to res.stamp(it.start) },
            ) {
                linkedFast = it.ifEmpty { null }
            }
            TextInput(
                stringResource(R.string.editor_observation),
                text,
                minLines = 5,
                supporting = stringResource(R.string.editor_observation_prompt),
            ) {
                text = it
            }
            FormHeading(stringResource(R.string.editor_optional_check_in))
            RatingField(
                stringResource(R.string.mood),
                stringResource(R.string.rating_low),
                stringResource(R.string.rating_good),
                mood,
            ) {
                mood = it
            }
            RatingField(
                stringResource(R.string.energy),
                stringResource(R.string.rating_low),
                stringResource(R.string.rating_high),
                energy,
            ) {
                energy = it
            }
            RatingField(
                stringResource(R.string.hunger),
                stringResource(R.string.rating_none),
                stringResource(R.string.rating_strong),
                hunger,
            ) {
                hunger = it
            }
            Text(
                stringResource(R.string.editor_ratings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (type in listOf("start", "past", "end", "fast", "journal"))
            TextInput(
                stringResource(R.string.tags),
                tags,
                supporting = stringResource(R.string.editor_tags_prompt),
            ) {
                tags = it
            }
        if (type in listOf("metric", "water")) {
            if (metric == null && type != "water") {
                val kinds =
                    MetricKind.entries.filter { a.settings.showWeight || it != MetricKind.WEIGHT }
                if (kinds.size > 1)
                    ConnectedChoice(
                        kinds.map { it to res.kindName(it) },
                        kind,
                    ) {
                        kindText = it.key
                        value = ""
                    }
            }
            NumberField(
                stringResource(R.string.editor_amount),
                value,
                suffix = unit(kind, a.settings),
                supporting =
                    if (kind == MetricKind.WATER) stringResource(R.string.editor_water_note)
                    else null,
            ) {
                value = it
            }
            if (kind == MetricKind.WATER && metric == null) {
                val amounts =
                    if (a.settings.useFluidOunces) listOf(8, 12, 16) else listOf(150, 250, 500)
                ChoiceChips(
                    amounts.map {
                        it to res.getString(R.string.amount, it.toDouble().number(0), res.unit(kind, a.settings))
                    },
                    amounts.firstOrNull { value.decimal() == it.toDouble() },
                ) {
                    value = it.toString()
                }
            }
        }
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val operationError by m.message.collectAsStateWithLifecycle()
        operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (type == "past" || type == "start")
            Text(
                stringResource(R.string.editor_early_finish_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (id != null)
            OutlinedButton(
                onClick = { delete = true },
                enabled = !busy,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (f?.end == null && f != null) R.string.editor_discard_fast else R.string.delete)
                )
            }
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_message)) },
            confirmButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text(stringResource(R.string.delete_record_title)) },
            text = { Text(stringResource(R.string.delete_record_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        m.mutate(
                            { current ->
                                when (type) {
                                    "fast",
                                    "end" -> current.deleteFast(id!!)
                                    "journal" ->
                                        current.copy(
                                            journal = current.journal.filterNot { it.id == id }
                                        )
                                    "metric",
                                    "water" ->
                                        current.copy(
                                            metrics = current.metrics.filterNot { it.id == id }
                                        )
                                    "plan" ->
                                        current.copy(
                                            plans = current.plans.filterNot { it.id == id }
                                        )
                                    "rest" ->
                                        current.copy(
                                            restDays = current.restDays.filterNot { it.id == id }
                                        )
                                    "preset" ->
                                        current.copy(
                                            presets = current.presets.filterNot { it.id == id }
                                        )
                                    else -> current
                                }
                            },
                            dismiss,
                        )
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { delete = false }) { Text(stringResource(R.string.keep)) }
            },
        )
}

@Composable
internal fun ToggleRow(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) =
    SwitchLine(label, enabled, onChange)

@Composable
private fun WaterSettingsDialog(m: FastModel, a: FastArchive, dismiss: () -> Unit) {
    val s = a.settings.water
    val res = LocalResources.current
    val unit = unit(MetricKind.WATER, a.settings)
    fun display(value: Double) = plain(Units.display(value, MetricKind.WATER, a.settings))
    var useGoal by rememberSaveable { mutableStateOf(s.goalMilliliters != null) }
    var goal by rememberSaveable { mutableStateOf(s.goalMilliliters?.let(::display).orEmpty()) }
    var glass by rememberSaveable { mutableStateOf(display(s.quickAddMilliliters)) }
    var reminders by rememberSaveable { mutableStateOf(s.remindersEnabled) }
    var paused by rememberSaveable { mutableStateOf(s.remindersPaused) }
    var start by rememberSaveable { mutableStateOf(s.startHour.toString()) }
    var end by rememberSaveable { mutableStateOf(s.endHour.toString()) }
    var interval by rememberSaveable { mutableStateOf(s.intervalHours.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (!it) m.message.value = res.getString(R.string.notifications_off_reminders)
        }
    val busy by m.working.collectAsStateWithLifecycle()
    fun save() {
        try {
            fun amount(raw: String) =
                Units.canonical(
                    raw.decimal() ?: error(res.getString(R.string.error_amount)),
                    MetricKind.WATER,
                    a.settings,
                )
            val settings =
                WaterSettings(
                    if (!useGoal) null
                    else if (goal == s.goalMilliliters?.let(::display)) s.goalMilliliters
                    else amount(goal),
                    if (glass == display(s.quickAddMilliliters)) s.quickAddMilliliters
                    else amount(glass),
                    reminders,
                    paused,
                    start.toInt(),
                    end.toInt(),
                    interval.toInt(),
                )
            settings.validate()
            m.mutate(
                { it.copy(settings = it.settings.copy(water = settings)) },
                {
                    if (reminders && !paused && Build.VERSION.SDK_INT >= 33)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    dismiss()
                },
            )
        } catch (e: Exception) {
            error = res.explain(e)
        }
    }
    val draft =
        listOf(useGoal, goal, glass, reminders, paused, start, end, interval).joinToString("|")
    val original = rememberSaveable { draft }
    FullScreenForm(
        stringResource(R.string.water_settings),
        dismiss,
        ::save,
        busy = busy,
        confirmDiscard = draft != original,
        saveEnabled =
            (!useGoal || (goal.decimal() ?: 0.0) > 0) &&
                (glass.decimal() ?: 0.0) > 0 &&
                (!reminders || start.toInt() < end.toInt()),
    ) {
        FormHeading(stringResource(R.string.water_goal_heading))
        SwitchLine(stringResource(R.string.water_use_goal), useGoal) { useGoal = it }
        if (useGoal)
            NumberField(stringResource(R.string.water_daily_amount), goal, suffix = unit) { goal = it }
        Text(
            stringResource(R.string.water_goal_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormHeading(stringResource(R.string.water_glass_heading))
        NumberField(
            stringResource(R.string.water_glass),
            glass,
            suffix = unit,
            supporting = stringResource(R.string.water_glass_note),
        ) {
            glass = it
        }
        fun hour(n: Int) =
            if (n == 24) res.getString(R.string.midnight)
            else
                SevenFormat.time(
                    java.time.LocalDate.now().atTime(n, 0).atZone(ZoneId.systemDefault()),
                    use24Hour,
                )
        FormHeading(stringResource(R.string.water_reminders_heading))
        SwitchLine(stringResource(R.string.water_remind), reminders) { reminders = it }
        if (reminders) {
            SwitchLine(stringResource(R.string.water_paused), paused) { paused = it }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                DropdownField(
                    stringResource(R.string.water_from),
                    start,
                    (0..23).map { it.toString() to hour(it) },
                    Modifier.weight(1f),
                ) {
                    start = it
                }
                DropdownField(
                    stringResource(R.string.water_until),
                    end,
                    (1..24).map { it.toString() to hour(it) },
                    Modifier.weight(1f),
                ) {
                    end = it
                }
            }
            DropdownField(
                stringResource(R.string.water_every),
                interval,
                (1..4).map { it.toString() to res.getQuantityString(R.plurals.water_interval, it, it) },
            ) {
                interval = it
            }
        }
        Text(
            stringResource(R.string.water_reminders_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reminders && start.toInt() >= end.toInt())
            Text(stringResource(R.string.water_end_after_start), color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val operationError by m.message.collectAsStateWithLifecycle()
        operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** A nullable rating: selecting a value records it; Clear restores the unrecorded state. */
@Composable
private fun RatingField(
    title: String,
    low: String,
    high: String,
    value: String,
    change: (String) -> Unit,
) {
    OptionalChoice(
        title,
        (1..5).map { it to it.toDouble().number(0) },
        value.toIntOrNull(),
        stringResource(R.string.rating_scale, low, high),
    ) {
        change(it?.toString().orEmpty())
    }
}
