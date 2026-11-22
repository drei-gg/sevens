@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.bloom

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import gg.seven.kit.SevenColors
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import org.json.JSONObject

internal val categories =
    listOf(
        "Cramps",
        "Headache",
        "Fatigue",
        "Bloating",
        "Calm",
        "Focused",
        "Energetic",
        "Happy",
        "Anxiety",
        "Low mood",
        "Back pain",
        "Breast tenderness",
        "Nausea",
        "Acne",
        "Hot flashes",
        "Night sweats",
        "Sleep difficulty",
        "Pelvic pain",
        "Dryness",
    )

@Composable
internal fun timeLabel(at: Long): String =
    android.text.format.DateFormat.getTimeFormat(LocalContext.current)
        .format(java.util.Date(at * 1000))

internal fun preference(c: android.content.Context) =
    c.getSharedPreferences("bloom-layout", android.content.Context.MODE_PRIVATE)

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
internal fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun BloomToday(
    model: BloomModel,
    a: JSONObject,
    d: JSONObject,
    edit: (String, JSONObject?) -> Unit,
    openGuides: () -> Unit,
) {
    var explanation by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.today_your_cycle), style = MaterialTheme.typography.labelLarge)
            Text(pretty(d.getString("today")), style = MaterialTheme.typography.labelMedium)
        }
        val estimate = if (d.isNull("estimate_days")) null else d.getInt("estimate_days")
        val cycle = if (d.isNull("cycle_day")) null else d.getInt("cycle_day")
        Text(
            when {
                !d.isNull("pregnancy_week") ->
                    stringResource(R.string.today_week, d.getInt("pregnancy_week"))
                estimate != null && estimate >= 0 ->
                    if (estimate == 0) stringResource(R.string.today_today)
                    else pluralStringResource(R.plurals.today_days, estimate, estimate)
                cycle != null -> stringResource(R.string.today_day, cycle)
                else -> stringResource(R.string.today_start)
            },
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            when {
                !d.isNull("pregnancy_week") -> stringResource(R.string.today_from_due)
                estimate != null && estimate >= 0 -> stringResource(R.string.today_until_estimate)
                cycle != null -> stringResource(R.string.today_since_period)
                else -> stringResource(R.string.today_context)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        val used = d.getInt("sample_size")
        val usedText = pluralStringResource(R.plurals.today_cycles_used, used, used)
        Muted(
            if (estimate == null) stringResource(R.string.today_no_forecast)
            else if (cycle != null) stringResource(R.string.today_cycle_day_basis, cycle, usedText)
            else usedText
        )
        val today = LocalDate.parse(d.getString("today"))
        val observed = d.rows("observed_days").associateBy { it.getString("day") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (6 downTo 0).forEach { offset ->
                val date = today.minusDays(offset.toLong())
                val row = observed[date.toString()]
                val flow = row != null && row.getString("flow") != "none"
                val described =
                    stringResource(
                        if (flow) R.string.day_flow_recorded
                        else if (row != null) R.string.day_observation_recorded
                        else R.string.day_unlogged,
                        pretty(date.toString()),
                    )
                Column(
                    Modifier.weight(1f).clearAndSetSemantics { contentDescription = described },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        date.dayOfWeek.getDisplayName(
                            java.time.format.TextStyle.NARROW,
                            LocalConfiguration.current.locales[0],
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Box(
                        Modifier.height(36.dp).fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(if (flow) 36.dp else 12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (flow) SevenColors.time
                                    else if (row != null) SevenColors.water
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            stringResource(R.string.today_strip_legend),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { explanation = !explanation }) {
            Text(
                stringResource(
                    if (explanation) R.string.today_estimate_hide else R.string.today_estimate_about
                )
            )
        }
        if (explanation) {
            Muted(d.getString("prediction_note"))
            Muted(stringResource(R.string.today_estimate_disclaimer))
        }
        Button(
            shapes = ButtonDefaults.shapesFor(56.dp),
            onClick = {
                edit(
                    "day",
                    a.rows("days").firstOrNull { it.getString("day") == d.getString("today") },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.today_log))
        }
        TextButton(onClick = { edit("observation", null) }) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_timed_observation))
        }
        if (preference(context).getBoolean("contacts", false))
            TextButton(onClick = { edit("contact", null) }) {
                Text(stringResource(R.string.today_log_contact))
            }
    }
    DoseList(model, a, d)
    if (d.rows("plan_positions").isNotEmpty()) {
        SectionTitle(stringResource(R.string.today_pack_progress))
        Panel {
            d.rows("plan_positions").forEach { position ->
                a.rows("plans")
                    .firstOrNull { it.getString("id") == position.getString("id") }
                    ?.getString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Muted(it) }
                PackProgress(position)
            }
        }
    }
    a.rows("observations")
        .maxByOrNull { it.getLong("at") }
        ?.let { event ->
            SectionTitle(stringResource(R.string.today_recent))
            Panel {
                ObservationSummary(event)
                TextButton(onClick = { edit("observation", event) }) {
                    Text(stringResource(R.string.today_review_entry))
                }
            }
        }
    if (d.rows("stock").isNotEmpty()) {
        SectionTitle(stringResource(R.string.today_supply))
        Panel {
            d.rows("stock").forEach {
                val remaining = it.getInt("remaining")
                Text(
                    pluralStringResource(
                        R.plurals.supply_named,
                        remaining,
                        it.getString("name"),
                        remaining,
                    )
                )
            }
        }
    }
    SectionTitle(stringResource(R.string.today_private))
    Panel {
        Muted(stringResource(R.string.today_private_body))
        TextButton(onClick = openGuides) { Text(stringResource(R.string.guides_title)) }
    }
}

/** A pill pack's position from the Rust core: day, phase and active/break split. */
@Composable
internal fun PackProgress(position: JSONObject) {
    val day = position.getInt("day")
    val total = position.getInt("total")
    val active = position.getInt("active")
    Text(
        stringResource(R.string.pack_day, day, total, valueText(position.getString("phase"))),
        style = MaterialTheme.typography.titleMedium,
    )
    val described = stringResource(R.string.pack_position, day, total)
    LinearProgressIndicator(
        progress = { day.toFloat() / total },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = described },
        color = SevenColors.water,
    )
    Muted(pluralStringResource(R.plurals.pack_split, total - active, active, total - active))
}

@Composable
internal fun ObservationSummary(row: JSONObject) {
    Text(
        row.getString("label").ifBlank { valueText(row.getString("kind")) },
        style = MaterialTheme.typography.titleMedium,
    )
    val value = row.getString("value")
    Muted(
        listOf(
                Instant.ofEpochSecond(row.getLong("at"))
                    .atZone(ZoneId.of(row.getString("zone")))
                    .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                row.getString("zone"),
                if (value.isEmpty()) "" else valueText(value),
                if (!row.isNull("level"))
                    stringResource(R.string.observation_level, row.getInt("level"))
                else "",
                if (row.isNull("number")) ""
                else if (row.getString("kind") == "temperature")
                    BloomTemperature.text(row.getDouble("number"))
                else
                    stringResource(
                        R.string.observation_ml,
                        java.text.NumberFormat.getInstance().format(row.getDouble("number")),
                    ),
            )
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    )
    if (row.optString("notes").isNotBlank()) Muted(row.getString("notes"))
}

@Composable
internal fun BloomCalendar(
    model: BloomModel,
    a: JSONObject,
    d: JSONObject,
    edit: (String, JSONObject?) -> Unit,
    remove: (String) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val search = rememberTextFieldState()
    val query = search.text.toString()
    var agenda by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(30) }
    var contacts by rememberSaveable { mutableStateOf(false) }
    val current = YearMonth.now()
    val earliest =
        (d.rows("observed_days").map { it.getString("day") } +
                a.rows("contacts").map { it.getString("day") })
            .minOrNull()
            ?.let { YearMonth.from(LocalDate.parse(it)) } ?: current.minusYears(10)
    val state =
        rememberCalendarState(
            startMonth = minOf(earliest, current.minusYears(10)),
            endMonth = current.plusYears(2),
            firstVisibleMonth = current,
            firstDayOfWeek = daysOfWeek().first(),
        )
    val scope = rememberCoroutineScope()
    val observed = d.rows("observed_days").associateBy { it.getString("day") }
    gg.seven.kit.ConnectedChoice(
        listOf(
            false to stringResource(R.string.calendar_month),
            true to stringResource(R.string.calendar_all),
        ),
        agenda,
    ) {
        agenda = it
    }
    if (!agenda && query.isBlank())
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val month = state.firstVisibleMonth.yearMonth
                IconButton(
                    onClick = {
                        scope.launch {
                            state.animateScrollToMonth(
                                maxOf(state.startMonth, month.minusMonths(1))
                            )
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        stringResource(R.string.calendar_previous),
                    )
                }
                Text(
                    month.format(localPattern("yMMMM", LocalConfiguration.current.locales[0])),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            state.animateScrollToMonth(minOf(state.endMonth, month.plusMonths(1)))
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        stringResource(R.string.calendar_next),
                    )
                }
            }
            Row {
                daysOfWeek().forEach {
                    Text(
                        it.getDisplayName(
                            java.time.format.TextStyle.NARROW,
                            LocalConfiguration.current.locales[0],
                        ),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            HorizontalCalendar(
                state = state,
                dayContent = { day ->
                    val row = observed[day.date.toString()]
                    val flow = row != null && row.getString("flow") != "none"
                    val start =
                        if (!d.isNull("earliest")) d.getString("earliest")
                        else d.optString("next_period")
                    val end =
                        if (!d.isNull("latest")) d.getString("latest")
                        else d.optString("next_period")
                    val estimate =
                        start != "null" &&
                            day.date.toString() >= start &&
                            day.date.toString() <= end
                    val isSelected = day.date.toString() == selected
                    val described =
                        stringResource(
                            if (flow) R.string.day_flow_recorded
                            else if (row != null) R.string.day_entry_recorded
                            else if (estimate) R.string.day_period_estimate
                            else R.string.day_unlogged,
                            pretty(day.date.toString()),
                        )
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { selected = day.date.toString() }
                            .semantics {
                                contentDescription = described
                                this.selected = isSelected
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            day.date.dayOfMonth.toString(),
                            color =
                                if (day.position == DayPosition.MonthDate)
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (flow)
                            Icon(
                                Icons.Outlined.WaterDrop,
                                null,
                                tint = SevenColors.time,
                                modifier = Modifier.size(16.dp),
                            )
                        else if (estimate)
                            Icon(
                                Icons.Outlined.RadioButtonUnchecked,
                                null,
                                modifier = Modifier.size(16.dp),
                            )
                        else if (row != null)
                            Box(
                                Modifier.size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SevenColors.water)
                            )
                        else Spacer(Modifier.height(16.dp))
                    }
                },
            )
            Text(
                stringResource(R.string.calendar_legend),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    LaunchedEffect(query) { limit = 30 }
    gg.seven.kit.SearchInput(stringResource(R.string.calendar_search), state = search)
    SectionTitle(
        if (agenda || query.isNotBlank()) stringResource(R.string.calendar_entries)
        else pretty(selected)
    )
    // An explicit start that the core counted in an earlier period says so on its day.
    if (!agenda && query.isBlank())
        d.rows("cycles")
            .firstOrNull { cycle ->
                cycle.optJSONArray("joined")?.let { joined ->
                    (0 until joined.length()).any { joined.getString(it) == selected }
                } == true
            }
            ?.let { cycle ->
                Muted(
                    stringResource(R.string.calendar_joined_start, shortDay(cycle.getString("start")))
                )
            }
    if (!agenda && selected <= LocalDate.now().toString()) {
        Button(
            shapes = ButtonDefaults.shapesFor(56.dp),
            onClick = {
                edit(
                    "day",
                    a.rows("days").firstOrNull { it.getString("day") == selected }
                        ?: newRecord("day").put("day", selected),
                )
            },
        ) {
            Text(stringResource(R.string.calendar_log_day))
        }
        TextButton(
            onClick = {
                val event = newRecord("observation")
                if (selected != LocalDate.now().toString())
                    event
                        .put("day", selected)
                        .put(
                            "at",
                            LocalDate.parse(selected)
                                .atTime(12, 0)
                                .atZone(ZoneId.systemDefault())
                                .toEpochSecond(),
                        )
                edit("observation", event)
            }
        ) {
            Text(stringResource(R.string.add_timed_observation))
        }
    }
    // Search the words people see and write, as iOS does; never the raw record JSON.
    fun searchable(kind: String, row: JSONObject): String =
        when (kind) {
                "day" ->
                    listOf(row.getString("notes"), row.getString("day")) +
                        row.getJSONArray("tags").let { t -> (0 until t.length()).map(t::getString) } +
                        row.getJSONObject("symptoms").keys().asSequence().toList()
                "observation" ->
                    listOf(row.getString("label"), row.getString("value"), row.getString("day"))
                else ->
                    listOf(row.getString("notes"), row.getString("activity"), row.getString("day"))
            }
            .joinToString(" ")
            .replace('_', ' ')
    fun matches(kind: String, row: JSONObject): Boolean =
        if (query.isNotBlank()) searchable(kind, row).contains(query.trim(), ignoreCase = true)
        else agenda || row.getString("day") == selected
    val rows =
        (a.rows("days").filter { matches("day", it) }.map { "day" to it } +
                a.rows("observations").filter { matches("observation", it) }.map { "observation" to it })
            .sortedByDescending { it.second.getString("day") + it.second.optLong("at", 0) }
    if (rows.isEmpty()) Muted(stringResource(R.string.calendar_empty))
    rows.take(limit).forEach { (kind, row) ->
        Panel {
            Text(pretty(row.getString("day")), style = MaterialTheme.typography.labelMedium)
            if (kind == "observation") ObservationSummary(row)
            else {
                Text(flowTitle(row.getString("flow")), style = MaterialTheme.typography.titleMedium)
                if (row.optString("notes").isNotBlank()) Muted(row.getString("notes"))
                Muted(row.getJSONObject("symptoms").keys().asSequence().joinToString(", "))
            }
            Row {
                TextButton(onClick = { edit(kind, row) }) { Text(stringResource(R.string.action_edit)) }
                TextButton(onClick = { remove("$kind:${row.getString("id")}") }) {
                    Text(stringResource(R.string.action_remove))
                }
            }
        }
    }
    if (rows.size > limit)
        TextButton(onClick = { limit += 30 }) { Text(stringResource(R.string.calendar_more)) }
    if (!agenda && query.isBlank()) {
        // Past doses that were never recorded can still be recorded from their day.
        val revision = a.getLong("revision")
        val scheduled by
            produceState(emptyList<JSONObject>(), selected, revision) {
                value =
                    try {
                        model.scheduledOn(selected)
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        emptyList()
                    }
            }
        if (scheduled.isNotEmpty()) {
            SectionTitle(stringResource(R.string.calendar_scheduled))
            scheduled.forEach { DoseCard(model, a, it) }
        }
    }
    Toggle(stringResource(R.string.calendar_contacts), contacts) { contacts = it }
    if (contacts) {
        TextButton(onClick = { edit("contact", null) }) {
            Text(stringResource(R.string.calendar_add_contact))
        }
        a.rows("contacts")
            .filter { matches("contact", it) }
            .sortedByDescending { it.getLong("at") }
            .take(limit)
            .forEach { row ->
                RecordCard(
                    stringResource(R.string.pair, pretty(row.getString("day")), row.getString("activity")),
                    row.getString("protection"),
                    { edit("contact", row) },
                    { remove("contact:${row.getString("id")}") },
                )
            }
    }
}

@Composable
internal fun ReminderControls(model: BloomModel) {
    val context = LocalContext.current
    val status by model.reminderStatus.collectAsStateWithLifecycle()
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.refresh()
        }
    Panel {
        Text(stringResource(R.string.reminders_title), style = MaterialTheme.typography.titleMedium)
        Muted(status)
        if (!BloomReminders.enabled(context))
            OutlinedButton(
                onClick = {
                    if (
                        android.os.Build.VERSION.SDK_INT >= 33 &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                }
            ) {
                Text(stringResource(R.string.reminders_allow))
            }
        if (android.os.Build.VERSION.SDK_INT >= 31 && !BloomReminders.exact(context))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            "package:${context.packageName}".toUri(),
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.reminders_precise))
            }
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        ) {
            Text(stringResource(R.string.reminders_settings))
        }
        Text(
            stringResource(R.string.reminders_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun BloomCare(
    model: BloomModel,
    a: JSONObject,
    d: JSONObject,
    edit: (String, JSONObject?) -> Unit,
    remove: (String) -> Unit,
) {
    DoseList(model, a, d)
    SectionTitle(stringResource(R.string.care_plans))
    Button(onClick = { edit("plan", null) }) { Text(stringResource(R.string.care_add_plan)) }
    if (a.rows("plans").isEmpty()) Muted(stringResource(R.string.care_no_plans))
    a.rows("plans").forEach { plan ->
        Panel {
            Text(plan.getString("name"), style = MaterialTheme.typography.titleLarge)
            val position =
                d.rows("plan_positions").firstOrNull { it.getString("id") == plan.getString("id") }
            if (position != null) PackProgress(position)
            else
                Muted(
                    stringResource(
                        R.string.pair,
                        valueText(plan.getString("pattern")),
                        stringResource(
                            if (plan.getBoolean("enabled")) R.string.plan_state_active
                            else R.string.plan_state_paused
                        ),
                    )
                )
            d.rows("stock")
                .firstOrNull { it.getString("plan_id") == plan.getString("id") }
                ?.let {
                    val remaining = it.getInt("remaining")
                    Text(
                        pluralStringResource(R.plurals.supply_remaining, remaining, remaining),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { edit("plan", plan) }) {
                    Text(stringResource(R.string.care_edit_plan))
                }
                if (plan.getBoolean("enabled"))
                    TextButton(onClick = { remove("plan:${plan.getString("id")}") }) {
                        Text(stringResource(R.string.care_pause))
                    }
                TextButton(
                    onClick = {
                        edit("refill", newRecord("refill").put("plan_id", plan.getString("id")))
                    }
                ) {
                    Text(stringResource(R.string.care_refill))
                }
            }
            var history by rememberSaveable(plan.getString("id")) { mutableStateOf(false) }
            TextButton(onClick = { history = !history }) {
                Text(
                    stringResource(
                        if (history) R.string.care_refills_hide else R.string.care_refills_show
                    )
                )
            }
            if (history)
                a.rows("refills")
                    .filter { it.getString("plan_id") == plan.getString("id") }
                    .sortedByDescending { it.getString("day") }
                    .forEach { refill ->
                        RecordCard(
                            stringResource(
                                R.string.care_refill_line,
                                pretty(refill.getString("day")),
                                refill.getInt("quantity"),
                            ),
                            "",
                            { edit("refill", refill) },
                            { remove("refill:${refill.getString("id")}") },
                        )
                    }
        }
    }
    ReminderControls(model)
    SectionTitle(stringResource(R.string.care_pregnancy_title))
    TextButton(onClick = { edit("pregnancy", null) }) {
        Text(stringResource(R.string.care_add_pregnancy))
    }
    a.rows("pregnancies").forEach { row ->
        RecordCard(
            stringResource(
                if (row.isNull("ended")) R.string.care_current_pregnancy
                else R.string.care_pregnancy_history
            ),
            stringResource(R.string.care_due, pretty(row.getString("due"))),
            { edit("pregnancy", row) },
            { remove("pregnancy:${row.getString("id")}") },
        )
    }
    TextButton(onClick = { edit("appointment", null) }) {
        Text(stringResource(R.string.care_add_appointment))
    }
    val appointmentFormat =
        localPattern(
            if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "MMMdHm"
            else "MMMdhm",
            LocalConfiguration.current.locales[0],
        )
    a.rows("appointments")
        .sortedBy { it.getLong("at") }
        .forEach { row ->
            RecordCard(
                row.getString("title"),
                Instant.ofEpochSecond(row.getLong("at"))
                    .atZone(ZoneId.systemDefault())
                    .format(appointmentFormat),
                { edit("appointment", row) },
                { remove("appointment:${row.getString("id")}") },
            )
        }
    SectionTitle(stringResource(R.string.care_dose_history))
    var limit by rememberSaveable { mutableIntStateOf(20) }
    a.rows("doses")
        .sortedByDescending { it.getLong("recorded") }
        .take(limit)
        .forEach { row ->
            val plan =
                a.rows("plans").firstOrNull { it.getString("id") == row.getString("plan_id") }
            RecordCard(
                stringResource(
                    R.string.pair,
                    pretty(row.getString("day")),
                    valueText(row.getString("status")),
                ),
                plan?.getString("name") ?: stringResource(R.string.care_archived_plan),
                { edit("dose", row) },
                { remove("dose:${row.getString("id")}") },
            )
        }
    if (a.rows("doses").size > limit)
        TextButton(onClick = { limit += 20 }) { Text(stringResource(R.string.care_more_doses)) }
    Muted(stringResource(R.string.care_missed_note))
}
