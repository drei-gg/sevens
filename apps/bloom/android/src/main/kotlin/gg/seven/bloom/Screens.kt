@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.bloom

import android.Manifest
import android.content.Intent
import android.os.Build
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

internal fun pretty(day: String) =
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

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
) {
    var explanation by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("YOUR CYCLE", style = MaterialTheme.typography.labelLarge)
            Text(pretty(d.getString("today")), style = MaterialTheme.typography.labelMedium)
        }
        val estimate = if (d.isNull("estimate_days")) null else d.getInt("estimate_days")
        val cycle = if (d.isNull("cycle_day")) null else d.getInt("cycle_day")
        Text(
            when {
                !d.isNull("pregnancy_week") -> "Week ${d.getInt("pregnancy_week")}"
                estimate != null && estimate >= 0 ->
                    if (estimate == 0) "Today"
                    else "$estimate ${if (estimate == 1) "day" else "days"}"
                cycle != null -> "Day $cycle"
                else -> "Start here"
            },
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            when {
                !d.isNull("pregnancy_week") -> "From your recorded due date"
                estimate != null && estimate >= 0 -> "Until your next period estimate"
                cycle != null -> "Since your last recorded period"
                else -> "A little context, at your own pace."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Muted(
            if (estimate != null)
                "Cycle day ${cycle ?: "—"} · ${d.getInt("sample_size")} completed cycles used"
            else "Your observations, without a forecast"
        )
        val today = LocalDate.parse(d.getString("today"))
        val observed = d.rows("observed_days").associateBy { it.getString("day") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (6 downTo 0).forEach { offset ->
                val date = today.minusDays(offset.toLong())
                val row = observed[date.toString()]
                val flow = row != null && row.getString("flow") != "none"
                Column(
                    Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription =
                            "${pretty(date.toString())}, ${if (flow) "flow recorded" else if (row != null) "observation recorded" else "unlogged"}"
                    },
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
            "Last 7 days · taller bars show recorded flow",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { explanation = !explanation }) {
            Text(if (explanation) "Hide estimate details" else "About this estimate")
        }
        if (explanation) {
            Muted(d.getString("prediction_note"))
            Muted("Estimates are not contraception or a diagnosis.")
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
            Text("Log today")
        }
        TextButton(onClick = { edit("observation", null) }) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add timed observation")
        }
        if (preference(context).getBoolean("contacts", false))
            TextButton(onClick = { edit("contact", null) }) { Text("Log sex/contact") }
    }
    DoseList(model, a, d)
    a.rows("observations")
        .maxByOrNull { it.getLong("at") }
        ?.let { event ->
            SectionTitle("Recently recorded")
            Panel {
                ObservationSummary(event)
                TextButton(onClick = { edit("observation", event) }) { Text("Review entry") }
            }
        }
    if (d.rows("stock").isNotEmpty()) {
        SectionTitle("Your supply")
        Panel {
            d.rows("stock").forEach {
                Text("${it.getString("name")} · ${it.getInt("remaining")} remaining")
            }
        }
    }
}

@Composable
internal fun ObservationSummary(row: JSONObject) {
    Text(
        row.getString("label").ifBlank {
            row.getString("kind").replace('_', ' ').replaceFirstChar(Char::uppercase)
        },
        style = MaterialTheme.typography.titleMedium,
    )
    Muted(
        listOf(
                Instant.ofEpochSecond(row.getLong("at"))
                    .atZone(ZoneId.of(row.getString("zone")))
                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                row.getString("zone"),
                row.getString("value").replace('_', ' '),
                if (!row.isNull("level")) "Level ${row.getInt("level")}/3" else "",
                if (!row.isNull("number"))
                    "${row.getDouble("number")} ${if (row.getString("kind") == "temperature") "°C" else "mL"}"
                else "",
            )
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    )
    if (row.optString("notes").isNotBlank()) Muted(row.getString("notes"))
}

@Composable
internal fun BloomCalendar(
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
    gg.seven.kit.ConnectedChoice(listOf(false to "Month", true to "All entries"), agenda) {
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
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous month")
                }
                Text(
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
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
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next month")
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
                                contentDescription =
                                    "${pretty(day.date.toString())}, ${if (flow) "flow recorded" else if (row != null) "entry recorded" else if (estimate) "period estimate" else "unlogged"}"
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
                "Drop: recorded flow · dot: entry · ring: estimate",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    LaunchedEffect(query) { limit = 30 }
    gg.seven.kit.SearchInput("Search notes, dates or observations", state = search)
    SectionTitle(if (agenda || query.isNotBlank()) "Your entries" else pretty(selected))
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
            Text("Log this day")
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
            Text("Add timed observation")
        }
    }
    fun matches(row: JSONObject): Boolean =
        if (query.isNotBlank()) row.toString().contains(query, ignoreCase = true)
        else agenda || row.getString("day") == selected
    val rows =
        (a.rows("days").filter(::matches).map { "day" to it } +
                a.rows("observations").filter(::matches).map { "observation" to it })
            .sortedByDescending { it.second.getString("day") + it.second.optLong("at", 0) }
    if (rows.isEmpty()) Muted("Nothing recorded here yet. Blank days stay unknown.")
    rows.take(limit).forEach { (kind, row) ->
        Panel {
            Text(pretty(row.getString("day")), style = MaterialTheme.typography.labelMedium)
            if (kind == "observation") ObservationSummary(row)
            else {
                Text(
                    "${row.getString("flow").replaceFirstChar(Char::uppercase)} flow",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (row.optString("notes").isNotBlank()) Muted(row.getString("notes"))
                Muted(row.getJSONObject("symptoms").keys().asSequence().joinToString(", "))
            }
            Row {
                TextButton(onClick = { edit(kind, row) }) { Text("Edit") }
                TextButton(onClick = { remove("$kind:${row.getString("id")}") }) { Text("Remove") }
            }
        }
    }
    if (rows.size > limit) TextButton(onClick = { limit += 30 }) { Text("Show more entries") }
    Toggle("Show private contact entries", contacts) { contacts = it }
    if (contacts) {
        TextButton(onClick = { edit("contact", null) }) { Text("Add contact") }
        a.rows("contacts")
            .filter(::matches)
            .sortedByDescending { it.getLong("at") }
            .take(limit)
            .forEach { row ->
                RecordCard(
                    "${pretty(row.getString("day"))} · ${row.getString("activity")}",
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
        Text("Reminder delivery", style = MaterialTheme.typography.titleMedium)
        Muted(status)
        if (!BloomReminders.enabled(context))
            OutlinedButton(
                onClick = {
                    if (
                        Build.VERSION.SDK_INT >= 33 &&
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
                Text("Allow notifications")
            }
        if (Build.VERSION.SDK_INT >= 31 && !BloomReminders.exact(context))
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
                Text("Allow precise reminders")
            }
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        ) {
            Text("Notification settings")
        }
        Text(
            "Android can delay alerts under device restrictions. After force-stopping the app, reopen it to restore reminders. Alerts never mark a dose as taken.",
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
    SectionTitle("Plans & supply")
    Button(onClick = { edit("plan", null) }) { Text("Add pill plan") }
    if (a.rows("plans").isEmpty())
        Muted("Make a plan from your prescription. Your schedule stays yours to adjust.")
    a.rows("plans").forEach { plan ->
        Panel {
            Text(plan.getString("name"), style = MaterialTheme.typography.titleLarge)
            val position =
                d.rows("plan_positions").firstOrNull { it.getString("id") == plan.getString("id") }
            if (position != null) {
                Text(
                    "Day ${position.getInt("day")} of ${position.getInt("total")} · ${position.getString("phase")}",
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { position.getInt("day").toFloat() / position.getInt("total") },
                    modifier = Modifier.fillMaxWidth(),
                    color = SevenColors.water,
                )
                Muted(
                    "${position.getInt("active")} active · ${position.getInt("total") - position.getInt("active")} break/placebo days"
                )
            } else
                Muted(
                    "${plan.getString("pattern")} · ${if (plan.getBoolean("enabled")) "active" else "paused"}"
                )
            d.rows("stock")
                .firstOrNull { it.getString("plan_id") == plan.getString("id") }
                ?.let {
                    Text(
                        "${it.getInt("remaining")} remaining",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { edit("plan", plan) }) { Text("Edit plan") }
                if (plan.getBoolean("enabled"))
                    TextButton(onClick = { remove("plan:${plan.getString("id")}") }) {
                        Text("Pause")
                    }
                TextButton(
                    onClick = {
                        edit("refill", newRecord("refill").put("plan_id", plan.getString("id")))
                    }
                ) {
                    Text("Record refill")
                }
            }
            var history by rememberSaveable(plan.getString("id")) { mutableStateOf(false) }
            TextButton(onClick = { history = !history }) {
                Text(if (history) "Hide refill history" else "Refill history")
            }
            if (history)
                a.rows("refills")
                    .filter { it.getString("plan_id") == plan.getString("id") }
                    .sortedByDescending { it.getString("day") }
                    .forEach { refill ->
                        RecordCard(
                            "${pretty(refill.getString("day"))} · +${refill.getInt("quantity")}",
                            "",
                            { edit("refill", refill) },
                            { remove("refill:${refill.getString("id")}") },
                        )
                    }
        }
    }
    ReminderControls(model)
    SectionTitle("Pregnancy & appointments")
    TextButton(onClick = { edit("pregnancy", null) }) { Text("Add pregnancy record") }
    a.rows("pregnancies").forEach { row ->
        RecordCard(
            if (row.isNull("ended")) "Current pregnancy" else "Pregnancy history",
            "Due ${pretty(row.getString("due"))}",
            { edit("pregnancy", row) },
            { remove("pregnancy:${row.getString("id")}") },
        )
    }
    TextButton(onClick = { edit("appointment", null) }) { Text("Add appointment") }
    a.rows("appointments")
        .sortedBy { it.getLong("at") }
        .forEach { row ->
            RecordCard(
                row.getString("title"),
                Instant.ofEpochSecond(row.getLong("at"))
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM d, HH:mm")),
                { edit("appointment", row) },
                { remove("appointment:${row.getString("id")}") },
            )
        }
    SectionTitle("Dose history")
    var limit by rememberSaveable { mutableIntStateOf(20) }
    a.rows("doses")
        .sortedByDescending { it.getLong("recorded") }
        .take(limit)
        .forEach { row ->
            val plan =
                a.rows("plans").firstOrNull { it.getString("id") == row.getString("plan_id") }
            RecordCard(
                "${pretty(row.getString("day"))} · ${row.getString("status")}",
                plan?.getString("name") ?: "Archived plan",
                { edit("dose", row) },
                { remove("dose:${row.getString("id")}") },
            )
        }
    if (a.rows("doses").size > limit)
        TextButton(onClick = { limit += 20 }) { Text("More dose history") }
    Muted(
        "For missed-dose instructions, check your medicine’s leaflet or ask a pharmacist. This app does not calculate replacement doses."
    )
}
