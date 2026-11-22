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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** "Nov 22, 2026" in the reader's locale; [FormatStyle.FULL] for TalkBack labels. */
internal fun dateLabel(date: LocalDate, style: FormatStyle = FormatStyle.MEDIUM): String =
    date.format(DateTimeFormatter.ofLocalizedDate(style))

/** A formatter for [skeleton] ("MMMyyyy", "EEEd") in the reader's locale and field order. */
@Composable
internal fun localPattern(skeleton: String): DateTimeFormatter {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return remember(locale, skeleton) {
        DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton),
            locale,
        )
    }
}

/** A calendar day for TalkBack: its date, recorded hours and what else is on it. */
private fun android.content.res.Resources.dayDescription(
    date: LocalDate,
    hours: Double,
    journal: Boolean,
    planned: Boolean,
    rest: Boolean,
): String =
    listOfNotNull(
            dateLabel(date, FormatStyle.FULL),
            getString(R.string.day_recorded, hoursText(hours)),
            getString(R.string.day_mark_journal).takeIf { journal },
            getString(R.string.day_mark_plan).takeIf { planned },
            getString(R.string.day_mark_rest).takeIf { rest },
        )
        .joinToString(getString(R.string.description_separator))

/** Heatmap shade: four steps by recorded hours, as on iOS, so the legend can name them. */
private fun shade(hours: Double): Float =
    when {
        hours < 6 -> 0.25f
        hours < 12 -> 0.45f
        hours < 18 -> 0.65f
        else -> 0.85f
    }

@Composable
internal fun HistoryScreen(
    m: FastModel,
    a: FastArchive,
    now: Double,
    open: OpenEditor,
    openAt: (String, Double) -> Unit,
) {
    val res = LocalResources.current
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
                        "Open ended" -> it.goalMinutes == null
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
            item { SearchInput(stringResource(R.string.history_search), state = search) }
            item {
                ConnectedChoice(
                    listOf(
                        "Calendar" to stringResource(R.string.history_calendar),
                        "Heatmap" to stringResource(R.string.history_heatmap),
                        "List" to stringResource(R.string.history_list),
                    ),
                    mode,
                ) {
                    mode = it
                }
            }
            item {
                Text(
                    stringResource(R.string.timezone_note, zone.id),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (mode == "Calendar" && query.isBlank())
                item { HistoryCalendar(a, day, now) { dayText = it.toString() } }
            if (mode == "Heatmap" && query.isBlank())
                item {
                    val end = LocalDate.parse(heatEnd)
                    Panel(stringResource(R.string.heatmap_title)) {
                        Text(stringResource(R.string.heatmap_detail))
                        val first = end.minusDays(90)
                        Text(
                            stringResource(R.string.date_range, dateLabel(first), dateLabel(end)),
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                                        localPattern("MMMyyyy")
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
                                val journal =
                                    included && a.journal.any { History.onDay(it.date, date, zone) }
                                val rest =
                                    included && a.restDays.any { History.onDay(it.date, date, zone) }
                                val planned =
                                    included && a.plans.any { History.onDay(it.start, date, zone) }
                                val color =
                                    if (hours == 0.0 || !included)
                                        MaterialTheme.colorScheme.surfaceVariant
                                    else SevenColors.time.copy(alpha = shade(hours))
                                Box(
                                    Modifier.padding(2.dp)
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color)
                                        .then(
                                            if (date == LocalDate.parse(dayText))
                                                Modifier.border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.onSurface,
                                                    RoundedCornerShape(8.dp),
                                                )
                                            else Modifier
                                        )
                                        .clickable(enabled = included) { dayText = date.toString() }
                                        .clearAndSetSemantics {
                                            contentDescription =
                                                if (included)
                                                    res.dayDescription(date, hours, journal, planned, rest)
                                                else res.getString(R.string.heatmap_outside)
                                            selected = date == LocalDate.parse(dayText)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // Same marks as iOS: a leaf for a rest day, else a dot for journal.
                                    if (rest)
                                        Icon(
                                            Icons.Outlined.Spa,
                                            null,
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    else if (journal)
                                        Box(
                                            Modifier.size(6.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(MaterialTheme.colorScheme.onSurface)
                                        )
                                }
                            },
                        )
                        HeatLegend()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { heatEnd = end.minusWeeks(13).toString() }) {
                                Text(stringResource(R.string.heatmap_earlier))
                            }
                            TextButton(onClick = { heatEnd = end.plusWeeks(13).toString() }) {
                                Text(stringResource(R.string.heatmap_later))
                            }
                        }
                    }
                }
            if (mode != "List" && query.isBlank())
                item {
                    Heading(
                        stringResource(
                            R.string.pair,
                            dateLabel(day, FormatStyle.FULL),
                            hoursText(History.hours(a, day, zone, now)),
                        )
                    )
                    a.journal
                        .filter { History.onDay(it.date, day, zone) }
                        .forEach { entry ->
                            TextButton(onClick = { open("journal", entry.id) }) {
                                Text(entry.text.ifBlank { stringResource(R.string.history_journal_check_in) })
                            }
                        }
                    a.plans
                        .filter { History.onDay(it.start, day, zone) }
                        .forEach { plan ->
                            TextButton(onClick = { open("plan", plan.id) }) {
                                Text(stringResource(R.string.history_plan, stamp(plan.start)))
                            }
                        }
                    a.restDays
                        .filter { History.onDay(it.date, day, zone) }
                        .forEach { rest ->
                            TextButton(onClick = { open("rest", rest.id) }) {
                                Text(
                                    if (rest.note.isBlank()) stringResource(R.string.rest_day)
                                    else stringResource(R.string.history_rest_note, rest.note)
                                )
                            }
                        }
                    val measured =
                        a.metrics
                            .filter {
                                History.onDay(it.date, day, zone) &&
                                    (it.kind != MetricKind.WEIGHT || a.settings.showWeight)
                            }
                            .sortedBy { it.date }
                    measured.forEach { metric ->
                        TextButton(
                            onClick = {
                                open(if (metric.kind == MetricKind.WATER) "water" else "metric", metric.id)
                            }
                        ) {
                            Text(
                                stringResource(
                                    R.string.pair,
                                    res.kindName(metric.kind),
                                    amount(metric.value, metric.kind, a.settings),
                                )
                            )
                        }
                    }
                    val hasFast = a.fasts.any {
                        History.hours(a.copy(fasts = listOf(it)), day, zone, now) > 0 ||
                            History.onDay(it.start, day, zone)
                    }
                    if (
                        !hasFast &&
                            measured.isEmpty() &&
                            a.journal.none { History.onDay(it.date, day, zone) } &&
                            a.plans.none { History.onDay(it.start, day, zone) } &&
                            a.restDays.none { History.onDay(it.date, day, zone) }
                    )
                        Text(
                            stringResource(R.string.history_nothing_recorded),
                            Modifier.padding(horizontal = Space.m),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            item {
                ChoiceChips(
                    listOf(
                        "All" to stringResource(R.string.history_filter_all),
                        "Goal reached" to stringResource(R.string.goal_reached),
                        "Shorter" to stringResource(R.string.history_filter_shorter),
                        "Open ended" to stringResource(R.string.open_ended),
                    ),
                    filter,
                ) {
                    filter = it
                }
            }
            if (records.isEmpty())
                item {
                    Empty(
                        stringResource(
                            if (query.isNotBlank()) R.string.history_no_match else R.string.history_empty
                        ),
                        stringResource(
                            if (query.isNotBlank()) R.string.history_no_match_detail
                            else R.string.history_empty_detail
                        ),
                    )
                }
            if (records.isNotEmpty())
                item {
                    Section {
                        records.forEach { fast ->
                            item(
                                res.duration(fast.duration(now)),
                                supporting =
                                    listOfNotNull(
                                            res.getString(
                                                R.string.date_range,
                                                res.stamp(fast.start),
                                                fast.end?.let { res.stamp(it) }
                                                    ?: res.getString(R.string.history_now),
                                            ),
                                            fast.intention.takeIf { it.isNotBlank() },
                                            when (fast.metGoal) {
                                                false -> res.getString(R.string.history_shorter_record)
                                                null ->
                                                    if (fast.end == null)
                                                        res.getString(R.string.history_in_progress)
                                                    else null
                                                true -> null
                                            },
                                        )
                                        .joinToString("\n"),
                                onClick = { open("detail", fast.id) },
                                trailing =
                                    if (fast.metGoal == true) {
                                        {
                                            Icon(
                                                Icons.Outlined.CheckCircle,
                                                stringResource(R.string.history_goal_met),
                                            )
                                        }
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
                        stringResource(if (adding) R.string.close else R.string.add),
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
                    // A past fast on the selected day: ending at noon (or now, for today).
                    openAt("past", pastEnd(day, zone))
                },
                text = { Text(stringResource(R.string.add_past_fast)) },
                icon = { Icon(Icons.Outlined.History, null) },
            )
            FloatingActionButtonMenuItem(
                onClick = {
                    adding = false
                    openAt("rest", pastEnd(day, zone))
                },
                text = { Text(stringResource(R.string.history_mark_rest)) },
                icon = { Icon(Icons.Outlined.Bedtime, null) },
            )
        }
    }
}

@Composable
internal fun JournalScreen(a: FastArchive, open: OpenEditor) {
    val res = LocalResources.current
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
            item { SearchInput(stringResource(R.string.journal_search), state = search) }
            item {
                Text(
                    stringResource(R.string.journal_intro),
                    Modifier.padding(horizontal = Space.m),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entries.isEmpty())
                item {
                    Empty(
                        stringResource(
                            if (query.isNotBlank()) R.string.journal_no_match else R.string.journal_empty
                        ),
                        stringResource(
                            if (query.isNotBlank()) R.string.journal_no_match_detail
                            else R.string.journal_empty_detail
                        ),
                    )
                }
            if (entries.isNotEmpty())
                item {
                    Section {
                        entries.forEach { entry ->
                            val ratings =
                                listOfNotNull(
                                    entry.mood?.let {
                                        res.getString(R.string.rating_value, res.getString(R.string.mood), it)
                                    },
                                    entry.energy?.let {
                                        res.getString(R.string.rating_value, res.getString(R.string.energy), it)
                                    },
                                    entry.hunger?.let {
                                        res.getString(R.string.rating_value, res.getString(R.string.hunger), it)
                                    },
                                )
                            item(
                                entry.text.ifBlank { res.getString(R.string.journal_check_in) },
                                supporting =
                                    listOfNotNull(
                                            res.stamp(entry.date),
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
            text = { Text(stringResource(R.string.journal_add)) },
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
                Triple(
                    stringResource(R.string.trends_recorded_hours),
                    stringResource(R.string.hours_short_value, summary.hours.number()),
                    null,
                ),
                Triple(
                    stringResource(R.string.trends_completed),
                    summary.sessions.toDouble().number(0),
                    null,
                ),
                Triple(
                    stringResource(R.string.trends_average),
                    summary.averageHours?.let { duration(it * 3_600_000) }
                        ?: stringResource(R.string.trends_none_yet),
                    null,
                ),
            ) +
                if (summary.goalsRecorded > 0)
                    listOf(
                        Triple(
                            stringResource(R.string.trends_goals_reached),
                            stringResource(R.string.ratio, summary.goalsMet, summary.goalsRecorded),
                            null,
                        )
                    )
                else emptyList()
        )
        Text(
            stringResource(R.string.trends_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DailyBars(a, end, days, now)
        WaterSummary(a, end, days)
        Heading(stringResource(R.string.trends_measurements))
        OutlinedButton(onClick = { open("metric", null) }) {
            Text(stringResource(R.string.log_measurement))
        }
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
                Panel(LocalResources.current.kindName(kind)) {
                    if (samples.isEmpty())
                        Text(
                            stringResource(R.string.trends_nothing_logged),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else {
                        MeasurementChart(samples, a.settings)
                        Text(
                            stringResource(
                                R.string.trends_first_latest,
                                Units.display(samples.first().value, kind, a.settings).number(),
                                Units.display(samples.last().value, kind, a.settings).number(),
                                unit(kind, a.settings),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        samples.asReversed().forEach { metric ->
                            TextButton(onClick = { open("metric", metric.id) }) {
                                Text(
                                    stringResource(
                                        R.string.pair,
                                        stamp(metric.date),
                                        amount(metric.value, kind, a.settings),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        val observations = a.journal.filter { it.date.atZone(zone).toLocalDate() in start..end }
        Panel(stringResource(R.string.trends_check_ins)) {
            Text(pluralStringResource(R.plurals.observations, observations.size, observations.size))
            listOf(
                    R.string.mood to observations.mapNotNull { it.mood },
                    R.string.energy to observations.mapNotNull { it.energy },
                    R.string.hunger to observations.mapNotNull { it.hunger },
                )
                .forEach { (name, values) ->
                    if (values.isNotEmpty())
                        Text(
                            stringResource(
                                R.string.rating_average,
                                stringResource(name),
                                values.average().number(),
                                values.size,
                            )
                        )
                }
            Text(
                stringResource(R.string.trends_check_ins_note),
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
            listOf(
                7 to stringResource(R.string.period_week),
                30 to stringResource(R.string.period_month),
                90 to stringResource(R.string.period_quarter),
                365 to stringResource(R.string.period_year),
            ),
            days,
            onSelect = onDays,
        )
        DateField(stringResource(R.string.period_ending), end, onChange = onEnd)
    }
}

@Composable
private fun DailyBars(a: FastArchive, end: LocalDate, count: Int, now: Double) {
    Panel(stringResource(R.string.trends_daily_hours)) {
        val values =
            remember(a.fasts, end, count, now.toLong() / 60_000) {
                (count - 1 downTo 0).map { offset ->
                    val day = end.minusDays(offset.toLong())
                    day to History.hours(a, day, ZoneId.systemDefault(), now)
                }
            }
        DailyChart(values, stringResource(R.string.unit_hours), SevenColors.time)
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
                                title.uppercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
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
    val res = LocalResources.current
    fun amount(value: Double) = res.amount(value, MetricKind.WATER, a.settings, 0)
    StatTiles(
        listOf(
            Triple(stringResource(R.string.water_total), amount(total), null),
            Triple(
                stringResource(R.string.water_days_with_entries),
                stringResource(R.string.ratio, logged.size, count),
                null,
            ),
        ) +
            if (logged.isNotEmpty())
                listOf(
                    Triple(
                        stringResource(R.string.water_average),
                        amount(total / logged.size),
                        null,
                    )
                )
            else emptyList()
    )
    if (logged.isNotEmpty())
        Panel(stringResource(R.string.water_daily)) {
            DailyChart(
                days.map {
                    it.date.atZone(zone).toLocalDate() to
                        if (it.entryCount == 0) null
                        else Units.display(it.milliliters, MetricKind.WATER, a.settings)
                },
                unit(MetricKind.WATER, a.settings),
                SevenColors.water,
            )
        }
    Text(
        stringResource(R.string.water_summary_note),
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
        Heading(stringResource(R.string.water_daily))
        History.waterDays(a.metrics, end, count, ZoneId.systemDefault()).reversed().forEach { day ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dateLabel(day.date.atZone(zone).toLocalDate()))
                Text(
                    if (day.entryCount == 0) stringResource(R.string.no_entries)
                    else amount(day.milliliters, MetricKind.WATER, a.settings, 0)
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
        if (entries.isEmpty())
            item {
                Empty(
                    stringResource(R.string.water_no_drinks),
                    stringResource(R.string.water_no_drinks_detail),
                )
            }
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
        trailingContent = { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) },
    ) {
        Text(amount(entry.value, entry.kind, a.settings, 0))
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
    val res = LocalResources.current
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
                Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.previous_day))
            }
            Box(Modifier.weight(1f)) {
                NativeDateTime(
                    stringResource(R.string.day),
                    day.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
                    false,
                ) {
                    dayText = it.instant().atZone(zone).toLocalDate().coerceAtMost(today).toString()
                }
            }
            IconButton(onClick = { dayText = day.plusDays(1).toString() }, enabled = day < today) {
                Icon(Icons.Outlined.ChevronRight, stringResource(R.string.next_day))
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
                Text(stringResource(R.string.water_set_goal))
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
                Text(amount(a.settings.water.quickAddMilliliters, MetricKind.WATER, a.settings, 0))
            }
            FilledTonalButton(onClick = { addAt(entryDate()) }) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.water_other_amount))
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
                Text(stringResource(R.string.water_undo_glass))
            }
        Heading(stringResource(R.string.water_drinks))
        if (entries.isEmpty())
            Text(
                stringResource(R.string.water_none_today),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            entries.forEachIndexed { index, entry ->
                val shapes = ListItemDefaults.segmentedShapes(index, entries.size)
                key(entry.id) {
                    val swipe = rememberSwipeToDismissBoxState()
                    LaunchedEffect(swipe.currentValue) {
                        // A swipe deletes at once, no question; Undo puts the same drink back
                        // (same ID and time), which works after the trial too.
                        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart)
                            m.mutate(
                                {
                                    it.copy(
                                        metrics = it.metrics.filterNot { e -> e.id == entry.id }
                                    )
                                },
                                onSaved = {
                                    m.undoable.value =
                                        FastModel.Undoable(res.getString(R.string.water_drink_deleted)) {
                                            m.restoreMetric(entry)
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
                                    stringResource(R.string.water_delete_drink),
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
        Panel(stringResource(R.string.water_last_seven)) {
            val week = History.waterDays(a.metrics, today, 7, zone)
            DailyChart(
                week.map {
                    it.date.atZone(zone).toLocalDate() to
                        if (it.entryCount == 0) null
                        else Units.display(it.milliliters, MetricKind.WATER, a.settings)
                },
                unit(MetricKind.WATER, a.settings),
                SevenColors.water,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { value ->
                    FilterChip(
                        selected = value.date.atZone(zone).toLocalDate() == day,
                        onClick = { dayText = value.date.atZone(zone).toLocalDate().toString() },
                        label = {
                            Text(
                                value.date.atZone(zone).format(
                                    localPattern("EEEd")
                                )
                            )
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.timezone_note, zone.id),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section {
            item(
                res.getString(R.string.page_water_trends),
                icon = Icons.Outlined.Insights,
                onClick = { onPage("Water trends") },
            )
            item(
                res.getString(R.string.page_water_entries),
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
    val res = LocalResources.current
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
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    stringResource(R.string.previous_month),
                )
            }
            Text(
                shown.format(
                    localPattern("MMMMyyyy")
                ),
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
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    stringResource(R.string.next_month),
                )
            }
        }
        if (LocalDensity.current.fontScale >= 1.5f || YearMonth.from(selectedDay) != shown)
            NativeDateTime(
                stringResource(R.string.calendar_jump),
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
                                res.dayDescription(day.date, hours, journal, planned, rest)
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
                                    if (hours > 0) hoursText(hours, android.icu.text.MeasureFormat.FormatWidth.NARROW)
                                    else "",
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
            stringResource(R.string.calendar_legend),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun FastDetailScreen(a: FastArchive, id: java.util.UUID?, now: Double, open: OpenEditor) {
    val fast = a.fasts.find { it.id == id }
    Page {
        if (fast == null)
            Empty(
                stringResource(R.string.detail_deleted),
                stringResource(R.string.detail_deleted_detail),
            )
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
                Text(stringResource(R.string.edit_fast))
            }
            val res = LocalResources.current
            Section {
                item(res.getString(R.string.started), res.stamp(fast.start))
                fast.end?.let { item(res.getString(R.string.finished), res.stamp(it)) }
                item(res.getString(R.string.goal), res.goal(fast.goalMinutes))
                item(res.getString(R.string.detail_time_zone), fast.timeZoneId)
                if (fast.tags.isNotEmpty()) item(res.getString(R.string.tags), fast.tags.joinToString(" · "))
            }
            if (fast.intention.isNotBlank())
                Section(title = stringResource(R.string.intention)) {
                    content {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(fast.intention)
                        }
                    }
                }
            if (fast.reflection.isNotBlank())
                Section(title = stringResource(R.string.reflection)) {
                    content {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(fast.reflection)
                        }
                    }
                }
            Section(title = stringResource(R.string.detail_observations)) {
                a.journal
                    .filter { it.fastId == id }
                    .sortedBy { it.date }
                    .forEach { entry ->
                        item(
                            entry.text.ifBlank { res.getString(R.string.detail_quiet_check_in) },
                            supporting = res.stamp(entry.date),
                            onClick = { open("journal", entry.id) },
                        )
                    }
                action(res.getString(R.string.detail_add_check_in), Icons.Outlined.EditNote) {
                    open("journal", null)
                }
            }
        }
    }
}

/** Noon on [day], or now if that's later than now: where a forgotten fast on that day ends. */
private fun pastEnd(day: LocalDate, zone: ZoneId): Double =
    day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli().toDouble().coerceAtMost(nowMillis())

/** What the heatmap's shades and marks mean (iOS HeatmapView's legend). */
@Composable
private fun HeatLegend() {
    val legend = stringResource(R.string.heatmap_legend_description)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = legend
            },
        ) {
            listOf(
                    0 to 0.0.number(0),
                    6 to 6.0.number(0),
                    12 to 12.0.number(0),
                    18 to stringResource(R.string.heatmap_or_more, 18.0.number(0)),
                )
                .forEach { (hours, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Box(
                        Modifier.size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (hours == 0) MaterialTheme.colorScheme.surfaceVariant
                                else SevenColors.time.copy(alpha = shade(hours.toDouble()))
                            )
                    )
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.unit_hours), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.heatmap_marks),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
