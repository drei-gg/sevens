@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package gg.seven.fast

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.HeatMapCalendar
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.heatmapcalendar.rememberHeatMapCalendarState
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import gg.seven.fast.core.*
import gg.seven.kit.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
internal fun HistoryScreen(m: FastModel, a: FastArchive, now: Double, open: OpenEditor) {
    var mode by rememberSaveable { mutableStateOf("Calendar") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var dayText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var heatEnd by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val day = LocalDate.parse(dayText)
    val zone = ZoneId.systemDefault()

    val records =
        a.fasts
            .sortedByDescending { it.start }
            .filter {
                it.searchableText.contains(query, ignoreCase = true) &&
                    when (filter) {
                        "Goal reached" -> it.metGoal == true
                        "Shorter" -> it.metGoal == false
                        else -> true
                    }
            }
            .filter {
                mode == "List" ||
                    History.hours(listOf(it), day, zone, now) > 0 ||
                    History.onDay(it.start, day, zone)
            }
    LazyColumn(
        Modifier.widthIn(max = 960.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = Space.m, end = Space.m, top = Space.s, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        item { SearchField(query, "Search intentions, reflections & tags") { query = it } }
        item { Segmented(listOf("Calendar", "Heatmap", "List").map { it to it }, mode) { mode = it } }
        item {
            Text(
                "Days follow your current timezone: ${zone.id}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mode == "Calendar") item { HistoryCalendar(a, day, now) { dayText = it.toString() } }
        if (mode == "Heatmap")
            item {
                val end = LocalDate.parse(heatEnd)
                Panel("13 weeks of recorded time") {
                    Text(
                        "Darker means more recorded hours. It is not a score. Scroll sideways for all 13 weeks."
                    )
                    val first = end.minusDays(90)
                    Text("$first — $end", style = MaterialTheme.typography.bodySmall)
                    val heat =
                        rememberHeatMapCalendarState(
                            startMonth = YearMonth.from(first),
                            endMonth = YearMonth.from(end),
                            firstVisibleMonth = YearMonth.from(end),
                        )
                    HeatMapCalendar(
                        state = heat,
                        monthHeader = { month ->
                            Text(
                                month.yearMonth.format(
                                    java.time.format.DateTimeFormatter.ofPattern("MMM yyyy")
                                ),
                                modifier = Modifier.padding(bottom = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        weekHeader = { weekday ->
                            Box(
                                Modifier.height(52.dp).padding(end = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    weekday.getDisplayName(
                                        java.time.format.TextStyle.SHORT,
                                        java.util.Locale.getDefault(),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        dayContent = { cell, _ ->
                            val date = cell.date
                            val included = date in first..end
                            val hours = History.hours(a.fasts, date, zone, now)
                            val color =
                                if (hours == 0.0 || !included)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else
                                    SevenColors.time.copy(
                                        alpha =
                                            (0.25 + hours / 24 * 0.75).toFloat().coerceAtMost(1f)
                                    )
                            Box(
                                Modifier.padding(2.dp)
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(color)
                                    .clickable(enabled = included) { dayText = date.toString() }
                                    .semantics {
                                        contentDescription =
                                            if (included) "$date, ${hours.number()} recorded hours"
                                            else "Outside selected period"
                                    }
                            )
                        },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { heatEnd = end.minusWeeks(13).toString() }) {
                            Text("Earlier 13 weeks")
                        }
                        TextButton(onClick = { heatEnd = end.plusWeeks(13).toString() }) {
                            Text("Later 13 weeks")
                        }
                    }
                }
            }
        if (mode != "List")
            item {
                Section("$day · ${History.hours(a.fasts, day, zone, now).number()} hours")
                a.journal
                    .filter { History.onDay(it.date, day, zone) }
                    .forEach { entry ->
                        TextButton(onClick = { open("journal", entry.id) }) {
                            Text(entry.text.ifBlank { "Journal check-in" })
                        }
                    }
                a.plans
                    .filter { History.onDay(it.start, day, zone) }
                    .forEach { plan ->
                        TextButton(onClick = { open("plan", plan.id) }) {
                            Text("Plan · ${stamp(plan.start)}")
                        }
                    }
                a.restDays
                    .filter { History.onDay(it.date, day, zone) }
                    .forEach { rest ->
                        TextButton(onClick = { open("rest", rest.id) }) {
                            Text("Rest day · ${rest.note}")
                        }
                    }
            }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SevenGroup {
                    MenuRow(
                        "Filter",
                        filter,
                        listOf(
                            "All" to "All fasts",
                            "Goal reached" to "Goal reached",
                            "Shorter" to "Shorter than planned",
                        ),
                    ) {
                        filter = it
                    }
                }
                SevenGroup {
                    ActionRow("Add past fast", leading = { Icon(Icons.Outlined.Add, null) }) {
                        open("past", null)
                    }
                    ActionRow("Mark a rest day", leading = { Icon(Icons.Outlined.Bedtime, null) }) {
                        open("rest", null)
                    }
                }
            }
        }
        if (records.isEmpty())
            item {
                Empty(
                    "A history worth keeping",
                    "Your records will be here. Add a forgotten fast whenever you need.",
                )
            }
        if (records.isNotEmpty())
            item {
                SevenGroup {
                    records.forEach { fast ->
                        SevenRow(
                            duration(fast.duration(now)),
                            supporting =
                                listOfNotNull(
                                        "${stamp(fast.start)} – ${fast.end?.let(::stamp) ?: "now"}",
                                        fast.intention.takeIf { it.isNotBlank() },
                                        when (fast.metGoal) {
                                            false -> "Shorter than planned · still a valid record"
                                            null -> if (fast.end == null) "In progress" else null
                                            true -> null
                                        },
                                    )
                                    .joinToString("\n"),
                            onClick = { open("detail", fast.id) },
                        ) {
                            if (fast.metGoal == true)
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    "Chosen time reached",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
    }
}

@Composable
internal fun JournalScreen(a: FastArchive, open: OpenEditor) {
    var query by rememberSaveable { mutableStateOf("") }
    val entries =
        a.journal
            .filter { (it.text + it.tags.joinToString()).contains(query, true) }
            .sortedByDescending { it.date }
    LazyColumn(
        Modifier.widthIn(max = 960.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = Space.m, end = Space.m, top = Space.s, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        item { SearchField(query, "Search observations & tags") { query = it } }
        item {
            Panel {
                Text("A record of how it felt.", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Set an intention. Notice what happens. Keep what you learn.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { open("journal", null) }) {
                    Icon(Icons.Outlined.EditNote, null)
                    Spacer(Modifier.width(Space.s))
                    Text("Add journal entry")
                }
            }
        }
        if (entries.isEmpty())
            item {
                Empty(
                    "Nothing required",
                    "A thought, an observation, or a reflection. Mood, energy and hunger are always optional.",
                )
            }
        if (entries.isNotEmpty())
            item {
                SevenGroup {
                    entries.forEach { entry ->
                        val ratings =
                            listOfNotNull(
                                entry.mood?.let { "Mood $it/5" },
                                entry.energy?.let { "Energy $it/5" },
                                entry.hunger?.let { "Hunger $it/5" },
                            )
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { open("journal", entry.id) }
                                .padding(horizontal = Space.m, vertical = Space.ms),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Text(
                                    stamp(entry.date),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(entry.text.ifBlank { "Check-in" }, maxLines = 3)
                                if (entry.tags.isNotEmpty())
                                    Text(
                                        entry.tags.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                if (ratings.isNotEmpty())
                                    Text(
                                        ratings.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall.numeric(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
    }
}

@Composable
internal fun TrendsScreen(a: FastArchive, now: Double, open: OpenEditor) {
    var days by rememberSaveable { mutableIntStateOf(30) }
    var endText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val end = LocalDate.parse(endText)
    val zone = ZoneId.systemDefault()
    val summary = History.summary(a, end, days, zone, now)
    Page {
        PeriodPicker(days, { days = it }, end, { endText = it.toString() })
        StatTiles(
            listOf(
                Triple("Recorded hours", "${summary.hours.number()} h", null),
                Triple("Completed fasts", summary.sessions.toString(), null),
                Triple(
                    "Average duration",
                    summary.averageHours?.let { duration(it * 3_600_000) } ?: "—",
                    null,
                ),
            ) +
                if (summary.goalsRecorded > 0)
                    listOf(
                        Triple(
                            "Goals reached",
                            "${summary.goalsMet} / ${summary.goalsRecorded}",
                            null,
                        )
                    )
                else emptyList()
        )
        Text(
            "Hours include portions of fasts in this period. Completed fasts and averages use sessions that ended in it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DailyBars(a, end, days, now)
        WaterSummary(a, end, days)
        Section("Measurements")
        OutlinedButton(onClick = { open("metric", null) }) { Text("Log measurement") }
        val start = end.minusDays(days.toLong() - 1)
        val metrics =
            a.metrics
                .filter {
                    it.kind != MetricKind.water &&
                        (a.settings.showWeight || it.kind != MetricKind.weight) &&
                        it.date.instant().atZone(zone).toLocalDate() in start..end
                }
                .sortedByDescending { it.date }
        listOf(MetricKind.weight, MetricKind.waist, MetricKind.sleep)
            .filter { a.settings.showWeight || it != MetricKind.weight }
            .forEach { kind ->
                val samples = metrics.filter { it.kind == kind }.sortedBy { it.date }
                Panel(kind.name.replaceFirstChar { it.uppercase() }) {
                    if (samples.isEmpty())
                        Text(
                            "Nothing logged in this period.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else {
                        MeasurementChart(samples, a.settings)
                        Text(
                            "First → latest: ${Units.display(samples.first().value,kind,a.settings).number()} → ${Units.display(samples.last().value,kind,a.settings).number()} ${Units.label(kind,a.settings)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        samples.asReversed().forEach { metric ->
                            TextButton(onClick = { open("metric", metric.id) }) {
                                Text(
                                    "${stamp(metric.date)} · ${Units.display(metric.value,kind,a.settings).number()} ${Units.label(kind,a.settings)}"
                                )
                            }
                        }
                    }
                }
            }
        val observations =
            a.journal.filter { it.date.instant().atZone(zone).toLocalDate() in start..end }
        Panel("Your check-ins") {
            Text("${observations.size} observations")
            listOf(
                    "Mood" to observations.mapNotNull { it.mood },
                    "Energy" to observations.mapNotNull { it.energy },
                    "Hunger" to observations.mapNotNull { it.hunger },
                )
                .forEach { (name, values) ->
                    if (values.isNotEmpty())
                        Text("$name · ${values.average().number()} / 5 · n=${values.size}")
                }
            Text(
                "These describe your entries. They do not show that fasting caused a change.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun PeriodPicker(
    days: Int,
    onDays: (Int) -> Unit,
    end: LocalDate,
    onEnd: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
        Segmented(listOf(7 to "Week", 30 to "Month", 90 to "Quarter", 365 to "Year"), days, onSelect = onDays)
        SevenGroup { DateRow("Ending", end, onChange = onEnd) }
    }
}

@Composable
private fun DailyBars(a: FastArchive, end: LocalDate, count: Int, now: Double) {
    Panel("Daily recorded hours") {
        val values =
            remember(a.fasts, end, count, now.toLong() / 60_000) {
                (count - 1 downTo 0).map { offset ->
                    val day = end.minusDays(offset.toLong())
                    day to History.hours(a.fasts, day, ZoneId.systemDefault(), now)
                }
            }
        DailyChart(values, "hours", SevenColors.time)
    }
}

@Composable
internal fun StatTiles(values: List<Triple<String, String, String?>>) {
    val columns = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        values.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (title, value, detail) ->
                    Card(
                        Modifier.weight(1f),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                title.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                value,
                                style =
                                    MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontFeatureSettings = "tnum",
                                    ),
                            )
                            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun WaterSummary(a: FastArchive, end: LocalDate, count: Int) {
    val days = History.waterDays(a.metrics, end, count, ZoneId.systemDefault())
    val logged = days.filter { it.entryCount > 0 }
    val total = days.sumOf { it.milliliters }
    fun amount(value: Double) =
        "${Units.display(value, MetricKind.water, a.settings).number(0)} ${Units.label(MetricKind.water, a.settings)}"
    StatTiles(
        listOf(
            Triple("Total logged", amount(total), null),
            Triple("Days with entries", "${logged.size} / $count", null),
        ) +
            if (logged.isNotEmpty())
                listOf(Triple("Average on logged days", amount(total / logged.size), null))
            else emptyList()
    )
    if (logged.isNotEmpty())
        Panel("Daily water") {
            DailyChart(
                days.map {
                    it.date to
                        if (it.entryCount == 0) null
                        else Units.display(it.milliliters, MetricKind.water, a.settings)
                },
                Units.label(MetricKind.water, a.settings),
                SevenColors.water,
            )
        }
    Text(
        "These are recorded amounts. A day without entries does not mean you drank no water.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun WaterTrendsScreen(a: FastArchive, now: Double) {
    var count by rememberSaveable { mutableIntStateOf(30) }
    var endText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val end = LocalDate.parse(endText)
    Page {
        PeriodPicker(
            count,
            { count = it },
            end,
            { endText = it.coerceAtMost(LocalDate.now()).toString() },
        )
        WaterSummary(a, end, count)
        Section("Daily water")
        History.waterDays(a.metrics, end, count, ZoneId.systemDefault()).reversed().forEach { day ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(day.date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")))
                Text(
                    if (day.entryCount == 0) "No entries"
                    else
                        "${Units.display(day.milliliters, MetricKind.water, a.settings).number(0)} ${Units.label(MetricKind.water, a.settings)}"
                )
            }
        }
    }
}

@Composable
internal fun WaterEntriesScreen(a: FastArchive, open: OpenEditor) {
    val entries = a.metrics.filter { it.kind == MetricKind.water }.sortedByDescending { it.date }
    LazyColumn(
        Modifier.widthIn(max = 960.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (entries.isEmpty()) item { Empty("No drinks logged", "Add a drink whenever you need.") }
        items(entries, key = { it.id }) { entry ->
            WaterEntryRow(entry, a) { open("water", entry.id) }
        }
    }
}

@Composable
private fun WaterEntryRow(entry: MetricEntry, a: FastArchive, click: () -> Unit) {
    Card(
        onClick = click,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    "${Units.display(entry.value, entry.kind, a.settings).number(0)} ${Units.label(entry.kind, a.settings)}",
                    fontWeight = FontWeight.SemiBold,
                )
            },
            supportingContent = { Text(stamp(entry.date)) },
            leadingContent = { Icon(Icons.Filled.WaterDrop, null, tint = SevenColors.water) },
            trailingContent = { Icon(Icons.Outlined.Edit, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
internal fun WaterScreen(
    m: FastModel,
    a: FastArchive,
    now: Double,
    open: OpenEditor,
    onPage: (String) -> Unit,
    addAt: (Double) -> Unit,
) {
    var dayText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var undoId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val day = LocalDate.parse(dayText)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val entries =
        a.metrics
            .filter { it.kind == MetricKind.water && History.onDay(it.date, day, zone) }
            .sortedByDescending { it.date }
    val busy by m.busy.collectAsState()
    fun entryDate() =
        day.atTime(LocalTime.now())
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
            .toDouble()
            .coerceAtMost(nowMillis())
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { dayText = day.minusDays(1).toString() }) {
                Icon(Icons.Outlined.ChevronLeft, "Previous day")
            }
            Box(Modifier.weight(1f)) {
                NativeDateTime(
                    "Day",
                    day.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
                    false,
                ) {
                    dayText = it.instant().atZone(zone).toLocalDate().coerceAtMost(today).toString()
                }
            }
            IconButton(onClick = { dayText = day.plusDays(1).toString() }, enabled = day < today) {
                Icon(Icons.Outlined.ChevronRight, "Next day")
            }
        }
        WaterAmount(a, day, large = true)
        if (a.settings.water.goalMilliliters == null)
            TextButton(
                onClick = { open("waterSettings", "settings") },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Outlined.TrackChanges, null)
                Spacer(Modifier.width(8.dp))
                Text("Set a daily goal")
            }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                enabled = !busy,
                onClick = {
                    if (!m.canCreate) addAt(entryDate())
                    else {
                        val entry =
                            MetricEntry(
                                date = entryDate(),
                                kind = MetricKind.water,
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
                    "${Units.display(a.settings.water.quickAddMilliliters,MetricKind.water,a.settings).number(0)} ${Units.label(MetricKind.water,a.settings)}"
                )
            }
            FilledTonalButton(onClick = { addAt(entryDate()) }) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(6.dp))
                Text("Other amount")
            }
        }
        if (undoId != null && a.metrics.any { it.id == undoId })
            TextButton(
                onClick = {
                    val id = undoId
                    m.mutate(
                        { it.copy(metrics = it.metrics.filterNot { e -> e.id == id }) },
                        onSaved = { undoId = null },
                    )
                }
            ) {
                Text("Undo last glass")
            }
        Section("Drinks")
        if (entries.isEmpty())
            Text(
                "No water logged for this day.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        entries.forEach { entry ->
            key(entry.id) {
                val swipe = rememberSwipeToDismissBoxState()
                LaunchedEffect(swipe.currentValue) {
                    if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
                        deleting = entry.id
                        swipe.reset()
                    }
                }
                SwipeToDismissBox(
                    state = swipe,
                    modifier = Modifier.clip(MaterialTheme.shapes.medium),
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(20.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                "Delete drink",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    },
                ) {
                    WaterEntryRow(entry, a) { open("water", entry.id) }
                }
            }
        }
        Panel("Last seven days") {
            val week = History.waterDays(a.metrics, today, 7, zone)
            DailyChart(
                week.map {
                    it.date to
                        if (it.entryCount == 0) null
                        else Units.display(it.milliliters, MetricKind.water, a.settings)
                },
                Units.label(MetricKind.water, a.settings),
                SevenColors.water,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { value ->
                    FilterChip(
                        selected = value.date == day,
                        onClick = { dayText = value.date.toString() },
                        label = { Text(value.date.format(DateTimeFormatter.ofPattern("EEE d"))) },
                    )
                }
            }
            Text(
                "Days follow your current timezone: ${zone.id}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = { onPage("Water trends") }, modifier = Modifier.fillMaxWidth()) {
            Text("Water trends")
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
        }
        OutlinedButton(
            onClick = { onPage("All water entries") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("All water entries")
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
        }
    }
    if (deleting != null)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this drink?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleting
                        m.mutate(
                            { it.copy(metrics = it.metrics.filterNot { e -> e.id == id }) },
                            onSaved = { deleting = null },
                        )
                    }
                ) {
                    Text("Delete drink")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
}

@Composable
private fun HistoryCalendar(
    a: FastArchive,
    selectedDay: LocalDate,
    now: Double,
    select: (LocalDate) -> Unit,
) {
    val state =
        rememberCalendarState(
            startMonth = YearMonth.of(1900, 1),
            endMonth = YearMonth.of(2199, 12),
            firstVisibleMonth = YearMonth.from(selectedDay),
            firstDayOfWeek = firstDayOfWeekFromLocale(),
        )
    val scope = rememberCoroutineScope()
    Panel {
        val shown = state.firstVisibleMonth.yearMonth
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    scope.launch { state.animateScrollToMonth(shown.minusMonths(1).coerceAtLeast(state.startMonth)) }
                }
            ) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous month")
            }
            Text(
                shown.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = {
                    scope.launch { state.animateScrollToMonth(shown.plusMonths(1).coerceAtMost(state.endMonth)) }
                }
            ) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next month")
            }
        }
        if (LocalDensity.current.fontScale >= 1.5f || YearMonth.from(selectedDay) != shown)
            NativeDateTime(
                "Jump to day",
                selectedDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble(),
                false,
            ) {
                val day = it.instant().atZone(ZoneId.systemDefault()).toLocalDate()
                select(day)
                scope.launch { state.scrollToMonth(YearMonth.from(day)) }
            }
        // Seven 48dp targets stay usable in narrow windows; horizontal scroll preserves targets at
        // large text.
        if (LocalDensity.current.fontScale < 1.5f)
            BoxWithConstraints {
                val width = maxWidth.coerceAtLeast(336.dp)
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    HorizontalCalendar(
                        state = state,
                        modifier = Modifier.width(width),
                        monthHeader = { month ->
                            Row(Modifier.padding(bottom = Space.s)) {
                                month.weekDays.first().forEach { day ->
                                    Text(
                                        day.date.dayOfWeek.getDisplayName(
                                            java.time.format.TextStyle.SHORT,
                                            java.util.Locale.getDefault(),
                                        ),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        },
                        monthBody = { month, content ->
                            Box {
                                content()
                                CalendarRings(
                                    month.weekDays.map { week -> week.map { it.date } },
                                    a,
                                    now,
                                    Modifier.matchParentSize(),
                                )
                            }
                        },
                        dayContent = { day ->
                            val hours =
                                History.hours(a.fasts, day.date, ZoneId.systemDefault(), now)
                            val journal =
                                a.journal.any {
                                    History.onDay(it.date, day.date, ZoneId.systemDefault())
                                }
                            val planned =
                                a.plans.any {
                                    History.onDay(it.start, day.date, ZoneId.systemDefault())
                                }
                            val rest =
                                a.restDays.any {
                                    History.onDay(it.date, day.date, ZoneId.systemDefault())
                                }
                            val description =
                                "${day.date}, ${hours.number()} recorded hours" +
                                    (if (journal) ", journal" else "") +
                                    (if (planned) ", plan" else "") +
                                    (if (rest) ", rest day" else "")
                            Column(
                                Modifier.fillMaxWidth()
                                    .height(80.dp)
                                    .padding(horizontal = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (day.date == selectedDay)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable { select(day.date) }
                                    .clearAndSetSemantics {
                                        contentDescription = description
                                        selected = day.date == selectedDay
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    day.date.dayOfMonth.toString(),
                                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                                )
                                Text(
                                    if (hours > 0) "${hours.number()}h" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    if (journal) "•"
                                    else if (planned) "○" else if (rest) "–" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                }
            }
        Text(
            "24-hour rings: midnight at top, noon at bottom. Linked across days; dashed for plans. Dot: journal · circle: plan · dash: rest day",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun FastDetailScreen(a: FastArchive, id: String?, now: Double, open: OpenEditor) {
    val fast = a.fasts.find { it.id == id }
    Page {
        if (fast == null)
            Empty("Fast deleted", "Your other records and observations are still here.")
        else {
            Text(
                duration(fast.duration(now)),
                style =
                    MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
            )
            Button(onClick = { open("fast", fast.id) }) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text("Edit fast")
            }
            SevenGroup {
                ValueRow("Started", stamp(fast.start))
                fast.end?.let { ValueRow("Finished", stamp(it)) }
                ValueRow("Goal", fast.goalMinutes?.let { duration(it * 60_000.0) } ?: "Open ended")
                ValueRow("Recorded time zone", fast.timeZoneID)
                if (fast.tags.isNotEmpty()) ValueRow("Tags", fast.tags.joinToString(" · "))
            }
            if (fast.intention.isNotBlank())
                SevenGroup(header = "Intention") {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(fast.intention, Modifier.fillMaxWidth().padding(Space.m))
                    }
                }
            if (fast.reflection.isNotBlank())
                SevenGroup(header = "Reflection") {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(fast.reflection, Modifier.fillMaxWidth().padding(Space.m))
                    }
                }
            SevenGroup(header = "Observations") {
                a.journal
                    .filter { it.fastID == id }
                    .sortedBy { it.date }
                    .forEach { entry ->
                        ValueRow(
                            entry.text.ifBlank { "A quiet check-in" },
                            supporting = stamp(entry.date),
                            chevron = true,
                            onClick = { open("journal", entry.id) },
                        )
                    }
                ActionRow("Add a check-in", leading = { Icon(Icons.Outlined.EditNote, null) }) {
                    open("journal", null)
                }
            }
        }
    }
}
