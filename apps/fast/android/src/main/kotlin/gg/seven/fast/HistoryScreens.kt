@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package gg.seven.fast

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
    val search = rememberTextFieldState()
    val query = search.text.toString()
    var filter by rememberSaveable { mutableStateOf("All") }
    var adding by rememberSaveable { mutableStateOf(false) }
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
                    query.isNotBlank() ||
                    History.hours(a.copy(fasts = listOf(it)), day, zone, now) > 0 ||
                    History.onDay(it.start, day, zone)
            }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.widthIn(max = 960.dp).fillMaxSize(),
            contentPadding =
                PaddingValues(start = Space.m, end = Space.m, top = Space.s, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            item { SearchInput("Search intentions, reflections & tags", state = search) }
            item {
                ConnectedChoice(listOf("Calendar", "Heatmap", "List").map { it to it }, mode) {
                    mode = it
                }
            }
            item {
                Text(
                    "Days follow your current timezone: ${zone.id}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (mode == "Calendar" && query.isBlank())
                item { HistoryCalendar(a, day, now) { dayText = it.toString() } }
            if (mode == "Heatmap" && query.isBlank())
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
                                            androidx.compose.ui.platform.LocalConfiguration.current
                                                .locales[0],
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            dayContent = { cell, _ ->
                                val date = cell.date
                                val included = date in first..end
                                val hours = History.hours(a, date, zone, now)
                                val color =
                                    if (hours == 0.0 || !included)
                                        MaterialTheme.colorScheme.surfaceVariant
                                    else
                                        SevenColors.time.copy(
                                            alpha =
                                                (0.25 + hours / 24 * 0.75)
                                                    .toFloat()
                                                    .coerceAtMost(1f)
                                        )
                                Box(
                                    Modifier.padding(2.dp)
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color)
                                        .clickable(enabled = included) { dayText = date.toString() }
                                        .semantics {
                                            contentDescription =
                                                if (included)
                                                    "$date, ${hours.number()} recorded hours"
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
            if (mode != "List" && query.isBlank())
                item {
                    Heading("$day · ${History.hours(a, day, zone, now).number()} hours")
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
                ChoiceChips(
                    listOf(
                        "All" to "All fasts",
                        "Goal reached" to "Goal reached",
                        "Shorter" to "Shorter than planned",
                    ),
                    filter,
                ) {
                    filter = it
                }
            }
            if (records.isEmpty())
                item {
                    Empty(
                        if (query.isNotBlank()) "No matching fasts" else "A history worth keeping",
                        if (query.isNotBlank())
                            "Try another word from an intention, reflection or tag."
                        else "Your records will be here. Add a forgotten fast whenever you need.",
                    )
                }
            if (records.isNotEmpty())
                item {
                    Section {
                        records.forEach { fast ->
                            item(
                                duration(fast.duration(now)),
                                supporting =
                                    listOfNotNull(
                                            "${stamp(fast.start)} – ${fast.end?.let(::stamp) ?: "now"}",
                                            fast.intention.takeIf { it.isNotBlank() },
                                            when (fast.metGoal) {
                                                false ->
                                                    "Shorter than planned · still a valid record"
                                                null ->
                                                    if (fast.end == null) "In progress" else null
                                                true -> null
                                            },
                                        )
                                        .joinToString("\n"),
                                onClick = { open("detail", fast.id) },
                                trailing =
                                    if (fast.metGoal == true) {
                                        { Icon(Icons.Outlined.CheckCircle, "Chosen time reached") }
                                    } else null,
                            )
                        }
                    }
                }
        }
        // Two ways to add: the Expressive FAB menu.
        FloatingActionButtonMenu(
            expanded = adding,
            modifier = Modifier.align(Alignment.BottomEnd),
            button = {
                ToggleFloatingActionButton(checked = adding, onCheckedChange = { adding = it }) {
                    // animateIcon gives the icon the container's content colour as it morphs.
                    Icon(
                        if (checkedProgress > 0.5f) Icons.Outlined.Close else Icons.Outlined.Add,
                        if (adding) "Close" else "Add",
                        with(ToggleFloatingActionButtonDefaults) {
                            Modifier.animateIcon({ checkedProgress })
                        },
                    )
                }
            },
        ) {
            FloatingActionButtonMenuItem(
                onClick = {
                    adding = false
                    open("past", null)
                },
                text = { Text("Add past fast") },
                icon = { Icon(Icons.Outlined.History, null) },
            )
            FloatingActionButtonMenuItem(
                onClick = {
                    adding = false
                    open("rest", null)
                },
                text = { Text("Mark a rest day") },
                icon = { Icon(Icons.Outlined.Bedtime, null) },
            )
        }
    }
}

@Composable
internal fun JournalScreen(a: FastArchive, open: OpenEditor) {
    val search = rememberTextFieldState()
    val query = search.text.toString()
    val entries =
        a.journal
            .filter { (it.text + it.tags.joinToString()).contains(query, true) }
            .sortedByDescending { it.date }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.widthIn(max = 960.dp).fillMaxSize(),
            contentPadding =
                PaddingValues(start = Space.m, end = Space.m, top = Space.s, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            item { SearchInput("Search observations & tags", state = search) }
            item {
                Text(
                    "A record of how it felt. Set an intention, notice what happens, keep what you learn.",
                    Modifier.padding(horizontal = Space.m),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entries.isEmpty())
                item {
                    Empty(
                        if (query.isNotBlank()) "No matching entries" else "Nothing required",
                        if (query.isNotBlank()) "Try another word from an observation or tag."
                        else
                            "A thought, an observation, or a reflection. Mood, energy and hunger are always optional.",
                    )
                }
            if (entries.isNotEmpty())
                item {
                    Section {
                        entries.forEach { entry ->
                            val ratings =
                                listOfNotNull(
                                    entry.mood?.let { "Mood $it/5" },
                                    entry.energy?.let { "Energy $it/5" },
                                    entry.hunger?.let { "Hunger $it/5" },
                                )
                            item(
                                entry.text.ifBlank { "Check-in" },
                                supporting =
                                    listOfNotNull(
                                            stamp(entry.date),
                                            entry.tags
                                                .takeIf { it.isNotEmpty() }
                                                ?.joinToString(" · "),
                                            ratings.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                                        )
                                        .joinToString("\n"),
                                onClick = { open("journal", entry.id) },
                            )
                        }
                    }
                }
        }
        ExtendedFloatingActionButton(
            onClick = { open("journal", null) },
            icon = { Icon(Icons.Outlined.EditNote, null) },
            text = { Text("Add journal entry") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.m),
        )
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
        Heading("Measurements")
        OutlinedButton(onClick = { open("metric", null) }) { Text("Log measurement") }
        val start = end.minusDays(days.toLong() - 1)
        val metrics =
            a.metrics
                .filter {
                    it.kind != MetricKind.WATER &&
                        (a.settings.showWeight || it.kind != MetricKind.WEIGHT) &&
                        it.date.atZone(zone).toLocalDate() in start..end
                }
                .sortedByDescending { it.date }
        listOf(MetricKind.WEIGHT, MetricKind.WAIST, MetricKind.SLEEP)
            .filter { a.settings.showWeight || it != MetricKind.WEIGHT }
            .forEach { kind ->
                val samples = metrics.filter { it.kind == kind }.sortedBy { it.date }
                Panel(kind.key.replaceFirstChar { it.uppercase() }) {
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
        val observations = a.journal.filter { it.date.atZone(zone).toLocalDate() in start..end }
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
        ConnectedChoice(
            listOf(7 to "Week", 30 to "Month", 90 to "Quarter", 365 to "Year"),
            days,
            onSelect = onDays,
        )
        DateField("Ending", end, onChange = onEnd)
    }
}

@Composable
private fun DailyBars(a: FastArchive, end: LocalDate, count: Int, now: Double) {
    Panel("Daily recorded hours") {
        val values =
            remember(a.fasts, end, count, now.toLong() / 60_000) {
                (count - 1 downTo 0).map { offset ->
                    val day = end.minusDays(offset.toLong())
                    day to History.hours(a, day, ZoneId.systemDefault(), now)
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
    val zone = ZoneId.systemDefault()
    val days = History.waterDays(a.metrics, end, count, zone)
    val logged = days.filter { it.entryCount > 0 }
    val total = days.sumOf { it.milliliters }
    fun amount(value: Double) =
        "${Units.display(value, MetricKind.WATER, a.settings).number(0)} ${Units.label(MetricKind.WATER, a.settings)}"
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
                    it.date.atZone(zone).toLocalDate() to
                        if (it.entryCount == 0) null
                        else Units.display(it.milliliters, MetricKind.WATER, a.settings)
                },
                Units.label(MetricKind.WATER, a.settings),
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
    val zone = ZoneId.systemDefault()
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
        Heading("Daily water")
        History.waterDays(a.metrics, end, count, ZoneId.systemDefault()).reversed().forEach { day ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(day.date.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy")))
                Text(
                    if (day.entryCount == 0) "No entries"
                    else
                        "${Units.display(day.milliliters, MetricKind.WATER, a.settings).number(0)} ${Units.label(MetricKind.WATER, a.settings)}"
                )
            }
        }
    }
}

@Composable
internal fun WaterEntriesScreen(a: FastArchive, open: OpenEditor) {
    val entries = a.metrics.filter { it.kind == MetricKind.WATER }.sortedByDescending { it.date }
    LazyColumn(
        Modifier.widthIn(max = 960.dp).fillMaxSize(),
        contentPadding =
            PaddingValues(start = Space.m, end = Space.m, top = Space.s, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (entries.isEmpty()) item { Empty("No drinks logged", "Add a drink whenever you need.") }
        itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
            WaterEntryRow(entry, a, ListItemDefaults.segmentedShapes(index, entries.size)) {
                open("water", entry.id)
            }
        }
    }
}

@Composable
private fun WaterEntryRow(
    entry: MetricEntry,
    a: FastArchive,
    shapes: ListItemShapes,
    click: () -> Unit,
) {
    SegmentedListItem(
        onClick = click,
        shapes = shapes,
        supportingContent = { Text(stamp(entry.date)) },
        leadingContent = { Icon(Icons.Filled.WaterDrop, null, tint = SevenColors.water) },
        trailingContent = { Icon(Icons.Outlined.Edit, "Edit") },
    ) {
        Text(
            "${Units.display(entry.value, entry.kind, a.settings).number(0)} ${Units.label(entry.kind, a.settings)}"
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
    var undoId by rememberSaveable { mutableStateOf<java.util.UUID?>(null) }
    val day = LocalDate.parse(dayText)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val entries =
        a.metrics
            .filter { it.kind == MetricKind.WATER && History.onDay(it.date, day, zone) }
            .sortedByDescending { it.date }
    val busy by m.working.collectAsState()
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
                onClick = { open("waterSettings", null) },
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
                            metricEntry(
                                date = entryDate(),
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
        Heading("Drinks")
        if (entries.isEmpty())
            Text(
                "No water logged for this day.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            entries.forEachIndexed { index, entry ->
                val shapes = ListItemDefaults.segmentedShapes(index, entries.size)
                key(entry.id) {
                    val swipe = rememberSwipeToDismissBoxState()
                    LaunchedEffect(swipe.currentValue) {
                        // A drink is easy to put back, so a swipe deletes it with Undo, no
                        // question.
                        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart)
                            m.mutate(
                                {
                                    it.copy(
                                        metrics = it.metrics.filterNot { e -> e.id == entry.id }
                                    )
                                },
                                onSaved = {
                                    m.undoable.value =
                                        FastModel.Undoable("Drink deleted") {
                                            m.mutate({ it.copy(metrics = it.metrics + entry) })
                                        }
                                },
                            )
                    }
                    SwipeToDismissBox(
                        state = swipe,
                        modifier = Modifier.clip(shapes.shape),
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
                        WaterEntryRow(entry, a, shapes) { open("water", entry.id) }
                    }
                }
            }
        }
        Panel("Last seven days") {
            val week = History.waterDays(a.metrics, today, 7, zone)
            DailyChart(
                week.map {
                    it.date.atZone(zone).toLocalDate() to
                        if (it.entryCount == 0) null
                        else Units.display(it.milliliters, MetricKind.WATER, a.settings)
                },
                Units.label(MetricKind.WATER, a.settings),
                SevenColors.water,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { value ->
                    FilterChip(
                        selected = value.date.atZone(zone).toLocalDate() == day,
                        onClick = { dayText = value.date.atZone(zone).toLocalDate().toString() },
                        label = {
                            Text(
                                value.date.atZone(zone).format(DateTimeFormatter.ofPattern("EEE d"))
                            )
                        },
                    )
                }
            }
            Text(
                "Days follow your current timezone: ${zone.id}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section {
            item(
                "Water trends",
                icon = Icons.Outlined.Insights,
                onClick = { onPage("Water trends") },
            )
            item(
                "All water entries",
                icon = Icons.Outlined.List,
                onClick = { onPage("All water entries") },
            )
        }
    }
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
                    scope.launch {
                        state.animateScrollToMonth(
                            shown.minusMonths(1).coerceAtLeast(state.startMonth)
                        )
                    }
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
                    scope.launch {
                        state.animateScrollToMonth(shown.plusMonths(1).coerceAtMost(state.endMonth))
                    }
                }
            ) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next month")
            }
        }
        if (LocalDensity.current.fontScale >= 1.5f || YearMonth.from(selectedDay) != shown)
            NativeDateTime(
                "Jump to day",
                selectedDay
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                    .toDouble(),
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
                                            androidx.compose.ui.platform.LocalConfiguration.current
                                                .locales[0],
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
                            val hours = History.hours(a, day.date, ZoneId.systemDefault(), now)
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
internal fun FastDetailScreen(a: FastArchive, id: java.util.UUID?, now: Double, open: OpenEditor) {
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
            FilledTonalButton(onClick = { open("fast", fast.id) }) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(Space.s))
                Text("Edit fast")
            }
            Section {
                item("Started", stamp(fast.start))
                fast.end?.let { item("Finished", stamp(it)) }
                item("Goal", fast.goalMinutes?.let { duration(it * 60_000.0) } ?: "Open ended")
                item("Recorded time zone", fast.timeZoneId)
                if (fast.tags.isNotEmpty()) item("Tags", fast.tags.joinToString(" · "))
            }
            if (fast.intention.isNotBlank())
                Section(title = "Intention") {
                    content {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(fast.intention)
                        }
                    }
                }
            if (fast.reflection.isNotBlank())
                Section(title = "Reflection") {
                    content {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(fast.reflection)
                        }
                    }
                }
            Section(title = "Observations") {
                a.journal
                    .filter { it.fastId == id }
                    .sortedBy { it.date }
                    .forEach { entry ->
                        item(
                            entry.text.ifBlank { "A quiet check-in" },
                            supporting = stamp(entry.date),
                            onClick = { open("journal", entry.id) },
                        )
                    }
                action("Add a check-in", Icons.Outlined.EditNote) { open("journal", null) }
            }
        }
    }
}
