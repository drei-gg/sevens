package gg.seven.bloom

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.*
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import gg.seven.kit.SevenColors
import java.time.LocalDate
import org.json.JSONObject

@Composable
private fun RecordChart(values: List<Pair<String, Double>>, temperature: Boolean = false) {
    if (values.isEmpty()) return
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values, temperature) {
        producer.runTransaction {
            if (temperature)
                lineSeries { series(x = values.indices.toList(), y = values.map { it.second }) }
            else columnSeries { series(x = values.indices.toList(), y = values.map { it.second }) }
        }
    }
    val color = if (temperature) SevenColors.water else SevenColors.time
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val formatter =
        remember(values, locale) {
            val numeric = localPattern("Md", locale)
            CartesianValueFormatter { _, value, _ ->
                values.getOrNull(value.toInt())?.first?.take(10)?.let {
                    LocalDate.parse(it).format(numeric)
                } ?: ""
            }
        }
    val described =
        stringResource(if (temperature) R.string.chart_temperatures else R.string.chart_cycles)
    val low = values.minOf { it.second }
    val high = values.maxOf { it.second }
    val padding = maxOf((high - low) * .1, .5)
    val point =
        remember(color) {
            LineCartesianLayer.Point(ShapeComponent(fill(color), CorneredShape.Pill), 6f)
        }
    ProvideVicoTheme(
        rememberM3VicoTheme(
            columnCartesianLayerColors = listOf(color),
            lineCartesianLayerColors = listOf(color),
        )
    ) {
        val layer =
            rememberLineCartesianLayer(
                lineProvider =
                    LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(
                            pointProvider = LineCartesianLayer.PointProvider.single(point)
                        )
                    ),
                rangeProvider =
                    CartesianLayerRangeProvider.fixed(minY = low - padding, maxY = high + padding),
            )
        CartesianChartHost(
            chart =
                if (temperature)
                    rememberCartesianChart(
                        layer,
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis =
                            HorizontalAxis.rememberBottom(
                                valueFormatter = formatter,
                                guideline = null,
                            ),
                    )
                else
                    rememberCartesianChart(
                        rememberColumnCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis =
                            HorizontalAxis.rememberBottom(
                                valueFormatter = formatter,
                                guideline = null,
                            ),
                    ),
            modelProducer = producer,
            zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
            animationSpec = null,
            animateIn = false,
            modifier =
                Modifier.fillMaxWidth().height(200.dp).clearAndSetSemantics {
                    contentDescription = described
                },
        )
    }
}

@Composable
internal fun BloomPatterns(
    model: BloomModel,
    a: JSONObject,
    d: JSONObject,
    edit: (String, JSONObject?) -> Unit,
    remove: (String) -> Unit,
) {
    var history by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(30) }
    Panel {
        Text(stringResource(R.string.patterns_rhythm), style = MaterialTheme.typography.titleLarge)
        Text(
            java.text.NumberFormat.getIntegerInstance().format(d.getInt("sample_size")),
            style = MaterialTheme.typography.displayLarge,
        )
        Muted(stringResource(R.string.patterns_cycles_used))
        val cycles =
            d.rows("cycles")
                .filter { !it.isNull("length") && it.isNull("excluded_reason") }
                .takeLast(12)
        RecordChart(cycles.map { it.getString("start") to it.getDouble("length") })
        Muted(d.getString("history_note"))
        TextButton(onClick = { history = !history }) {
            Text(
                stringResource(
                    if (history) R.string.patterns_history_hide else R.string.patterns_history_show
                )
            )
        }
        if (history)
            d.rows("cycles").asReversed().take(limit).forEach { cycle ->
                val bleeding = cycle.getInt("bleeding_days")
                Text(
                    stringResource(
                        R.string.patterns_cycle,
                        pretty(cycle.getString("start")),
                        if (cycle.isNull("length")) stringResource(R.string.patterns_current)
                        else
                            cycle.getInt("length").let {
                                pluralStringResource(R.plurals.days_count, it, it)
                            },
                        pluralStringResource(R.plurals.patterns_bleeding, bleeding, bleeding),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!cycle.isNull("excluded_reason"))
                    Muted(
                        stringResource(
                            R.string.patterns_excluded,
                            valueText(cycle.getString("excluded_reason")),
                        )
                    )
            }
        if (history && d.rows("cycles").size > limit)
            TextButton(onClick = { limit += 30 }) { Text(stringResource(R.string.patterns_more)) }
    }
    SectionTitle(stringResource(R.string.patterns_feel))
    if (d.rows("symptoms").isEmpty()) Panel { Muted(stringResource(R.string.patterns_feel_empty)) }
    d.rows("symptoms").take(20).forEach { row ->
        Panel {
            Text(row.getString("name"), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { row.getInt("count").toFloat() / maxOf(1, row.getInt("logged_days")) },
                color = SevenColors.time,
                modifier = Modifier.fillMaxWidth(),
            )
            val logged = row.getInt("logged_days")
            Text(
                pluralStringResource(
                    R.plurals.patterns_symptom_days,
                    logged,
                    row.getInt("count"),
                    logged,
                )
            )
            val before = row.getInt("preperiod_count")
            Muted(
                pluralStringResource(
                    R.plurals.patterns_symptom_detail,
                    before,
                    "%.1f".format(row.getDouble("average")),
                    before,
                )
            )
        }
    }
    val temperatures = d.rows("temperatures").takeLast(90)
    if (temperatures.isNotEmpty())
        Panel {
            Text(
                stringResource(R.string.patterns_temperature, BloomTemperature.unit),
                style = MaterialTheme.typography.titleLarge,
            )
            RecordChart(
                temperatures.map {
                    it.getString("day") to BloomTemperature.display(it.getDouble("value"))
                },
                true,
            )
            Muted(stringResource(R.string.patterns_temperature_note))
            var values by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { values = !values }) {
                Text(
                    stringResource(
                        if (values) R.string.patterns_measurements_hide
                        else R.string.patterns_measurements_show
                    )
                )
            }
            if (values)
                temperatures.asReversed().forEach {
                    val day = pretty(it.getString("day"))
                    val reading = BloomTemperature.text(it.getDouble("value"))
                    Text(
                        if (it.isNull("at"))
                            stringResource(R.string.patterns_measurement_daily, day, reading)
                        else
                            stringResource(
                                R.string.patterns_measurement_timed,
                                day,
                                timeLabel(it.getLong("at")),
                                reading,
                            )
                    )
                }
        }
    SectionTitle(stringResource(R.string.patterns_exclusions))
    Muted(stringResource(R.string.patterns_exclusions_note))
    OutlinedButton(onClick = { edit("exclusion", null) }) {
        Text(stringResource(R.string.patterns_exclude))
    }
    a.rows("exclusions").forEach { row ->
        RecordCard(
            row.getString("reason"),
            stringResource(
                R.string.patterns_range,
                pretty(row.getString("start")),
                if (row.isNull("end")) stringResource(R.string.exclusion_ongoing)
                else pretty(row.getString("end")),
            ),
            { edit("exclusion", row) },
            { remove("exclusion:${row.getString("id")}") },
        )
    }
    SectionTitle(stringResource(R.string.patterns_reports))
    ReportPanel(model)
}

@Composable
private fun ReportPanel(model: BloomModel) {
    var from by rememberSaveable { mutableStateOf(LocalDate.now().minusMonths(3).toString()) }
    var to by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var contacts by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    // The report text can include notes and contacts. It stays in the ViewModel, out of the
    // saved-state Bundle; only the chosen format survives recreation.
    val preview by model.report.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    fun changed() {
        model.report.value = null
    }
    Panel {
        DateField(stringResource(R.string.field_from), from) {
            from = it
            changed()
        }
        DateField(stringResource(R.string.field_through), to) {
            to = it
            changed()
        }
        Check(stringResource(R.string.report_contacts), contacts) {
            contacts = it
            changed()
        }
        Check(stringResource(R.string.report_notes), notes) {
            notes = it
            changed()
        }
        Button(enabled = !busy, onClick = { model.buildReport(from, to, contacts, notes) }) {
            Text(stringResource(R.string.report_preview))
        }
        Muted(stringResource(R.string.report_note))
        if (preview != null) {
            Text(stringResource(R.string.report_ready), style = MaterialTheme.typography.titleMedium)
            var show by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { show = !show }) {
                Text(stringResource(if (show) R.string.report_text_hide else R.string.report_text_show))
            }
            if (show) Text(preview!!, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pdf" to R.string.report_share_pdf, "txt" to R.string.report_share_text)
                    .forEach { (format, label) ->
                        FilledTonalButton(
                            enabled = !busy,
                            onClick = { model.shareReport(format) },
                        ) {
                            Text(stringResource(label))
                        }
                    }
                listOf("pdf" to R.string.report_save_pdf, "txt" to R.string.report_save_text)
                    .forEach { (format, label) ->
                        TextButton(
                            enabled = !busy,
                            onClick = { model.exportReport(format) },
                        ) {
                            Text(stringResource(label))
                        }
                    }
            }
            Muted(stringResource(R.string.report_copy_note))
        }
    }
}
