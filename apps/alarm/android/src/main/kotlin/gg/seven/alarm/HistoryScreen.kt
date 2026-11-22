@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.alarm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import gg.seven.alarm.core.EventKind
import gg.seven.alarm.core.LoopEvent
import gg.seven.kit.DateField
import gg.seven.kit.DateTimeField
import gg.seven.kit.FullScreenForm
import gg.seven.kit.SearchInput
import gg.seven.kit.Section
import gg.seven.kit.SevenColors
import gg.seven.kit.Space
import gg.seven.kit.TextInput
import gg.seven.kit.item
import gg.seven.kit.numeric
import gg.seven.kit.switch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * What you recorded (iOS HistoryScreen): this week's tiles and a chart first, then every record
 * grouped by day. Only recorded actions count; a scheduled time passing is never shown as done.
 */
@Composable
fun HistoryScreen(model: AlarmModel) {
    val archive by model.engine.archive.collectAsStateWithLifecycle()
    val editing by model.eventDraft.collectAsStateWithLifecycle()
    val search = rememberTextFieldState()
    val query = search.text.toString()
    var filter by rememberSaveable { mutableStateOf(false) }
    var dayEpoch by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val zone = ZoneId.systemDefault()
    val day = LocalDate.ofEpochDay(dayEpoch)
    fun dayOf(e: LoopEvent) = e.date.atZone(zone).toLocalDate()
    val events =
        archive.events
            .filter {
                (query.isEmpty() ||
                    it.name.contains(query, true) ||
                    it.note.contains(query, true)) && (!filter || dayOf(it) == day)
            }
            .sortedByDescending { it.date }
    val days = events.groupBy(::dayOf).toSortedMap(compareByDescending { it })
    LazyColumn(
        Modifier.fillMaxSize().testTag("history"),
        contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item(key = "search") {
            SearchInput(stringResource(R.string.history_search), state = search)
        }
        if (archive.events.isNotEmpty() && query.isEmpty())
            item(key = "summary") { Summary(archive.events) }
        item(key = "filter") {
            val showOneDay = stringResource(R.string.history_show_one_day)
            Section(
                Modifier.padding(top = Space.s),
                footer = stringResource(R.string.history_footer),
            ) {
                switch(showOneDay, filter) { filter = it }
            }
            if (filter)
                DateField(
                    stringResource(R.string.history_day),
                    day,
                    Modifier.padding(top = Space.s),
                ) {
                    dayEpoch = minOf(it, LocalDate.now()).toEpochDay()
                }
        }
        if (events.isEmpty())
            item(key = "empty") {
                gg.seven.kit.EmptyState(
                    if (query.isEmpty()) stringResource(R.string.history_empty)
                    else stringResource(R.string.history_no_results, query),
                    if (query.isEmpty()) stringResource(R.string.history_empty_body)
                    else stringResource(R.string.history_no_results_body),
                    icon = Icons.Outlined.CalendarMonth,
                )
            }
        days.forEach { (date, group) ->
            item(key = "day-$date") { Heading(dayTitle(date), Modifier.padding(top = Space.s)) }
            itemsIndexed(group, key = { _, e -> e.id }) { index, event ->
                SegmentedListItem(
                    onClick = { model.eventDraft.value = event },
                    shapes = ListItemDefaults.segmentedShapes(index, group.size),
                    modifier =
                        Modifier.padding(bottom = ListItemDefaults.SegmentedGap)
                            .testTag("event-${event.name}"),
                    leadingContent = {
                        Icon(
                            event.kind.icon,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    supportingContent = {
                        Column {
                            Text(event.kind.title)
                            if (event.note.isNotEmpty())
                                Text(
                                    event.note,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        }
                    },
                    trailingContent = {
                        Text(
                            ClockFormat.time(event.date),
                            style = MaterialTheme.typography.bodyMedium.numeric(),
                        )
                    },
                ) {
                    Text(event.name)
                }
            }
        }
    }
    editing?.let { EventEditor(model, it) }
}

private fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> Words.get(R.string.day_today)
        today.minusDays(1) -> Words.get(R.string.day_yesterday)
        else -> ClockFormat.longDay(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
    }
}

/**
 * Two tiles for the last seven days and a bar a day in the time hue (Vico, the Swift Charts
 * counterpart).
 */
@Composable
private fun Summary(all: List<LoopEvent>) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val recent = all.filter { !it.date.atZone(zone).toLocalDate().isBefore(week.first()) }
    val missions = recent.count { it.kind == EventKind.COMPLETED }
    val counts = week.map { d -> d to recent.count { it.date.atZone(zone).toLocalDate() == d } }
    Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.ms)) {
        val tiles: @Composable (Modifier) -> Unit = { m ->
            StatTile(
                stringResource(R.string.history_last_week),
                "${recent.size}",
                pluralStringResource(R.plurals.history_recorded_actions, recent.size),
                m,
            )
            StatTile(
                stringResource(R.string.history_missions),
                "$missions",
                stringResource(R.string.history_completed),
                m,
            )
        }
        if (largeText())
            Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                tiles(Modifier.fillMaxWidth())
            }
        else
            Row(horizontalArrangement = Arrangement.spacedBy(Space.ms)) {
                tiles(Modifier.weight(1f))
            }
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    stringResource(R.string.history_each_day),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WeekChart(counts)
            }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, detail: String, modifier: Modifier) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(Space.ms).heightIn(min = 64.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.titleLarge.numeric())
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeekChart(counts: List<Pair<LocalDate, Int>>) {
    val res = LocalResources.current
    val spoken =
        res.getString(
            R.string.history_chart_spoken,
            counts.joinToString(", ") { (d, n) ->
                res.getString(
                    R.string.history_chart_day,
                    d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    n,
                )
            },
        )
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(counts) {
        producer.runTransaction {
            columnSeries {
                series(x = counts.map { it.first.toEpochDay() }, y = counts.map { it.second })
            }
        }
    }
    val days = remember {
        CartesianValueFormatter { _, value, _ ->
            LocalDate.ofEpochDay(value.toLong())
                .dayOfWeek
                .getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
    }
    val counted = remember { CartesianValueFormatter { _, value, _ -> value.toInt().toString() } }
    ProvideVicoTheme(rememberM3VicoTheme(columnCartesianLayerColors = listOf(SevenColors.time))) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    startAxis =
                        VerticalAxis.rememberStart(
                            valueFormatter = counted,
                            itemPlacer = remember { VerticalAxis.ItemPlacer.step({ 1.0 }) },
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(valueFormatter = days, guideline = null),
                ),
            modelProducer = producer,
            animationSpec = null,
            animateIn = false,
            modifier =
                Modifier.fillMaxWidth().height(140.dp).clearAndSetSemantics {
                    contentDescription = spoken
                },
        )
    }
}

/** Correcting a record (iOS EventEditor): its time and note; corrections are marked manual. */
@Composable
private fun EventEditor(model: AlarmModel, event: LoopEvent) {
    var confirmDelete by remember { mutableStateOf(false) }
    val busy by model.engine.busy.collectAsStateWithLifecycle()
    FullScreenForm(
        busy = busy,
        title = stringResource(R.string.history_edit_title),
        onClose = { model.eventDraft.value = null },
        onSave = { model.saveEvent(event) { model.eventDraft.value = null } },
    ) {
        val routineLabel = stringResource(R.string.history_routine)
        val recordedAs = stringResource(R.string.history_recorded_as)
        Section {
            item(routineLabel, event.name)
            item(recordedAs, event.kind.title)
        }
        DateTimeField(
            stringResource(R.string.history_recorded_time),
            event.date.toEpochMilli().toDouble(),
        ) { millis ->
            val at = Instant.ofEpochMilli(millis.toLong())
            model.eventDraft.value = event.copy(date = minOf(at, Instant.now()))
        }
        TextInput(stringResource(R.string.history_note), event.note, minLines = 3) {
            model.eventDraft.value = event.copy(note = it)
        }
        Text(
            stringResource(R.string.history_corrections),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { confirmDelete = true },
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.align(Alignment.Start),
        ) {
            Icon(Icons.Outlined.Delete, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.history_delete))
        }
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        model.deleteEvent(event) { model.eventDraft.value = null }
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
}
