package gg.seven.bloom

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
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
    val formatter =
        remember(values) {
            CartesianValueFormatter { _, value, _ ->
                values.getOrNull(value.toInt())?.first?.take(10)?.let {
                    LocalDate.parse(it).let { date -> "${date.monthValue}/${date.dayOfMonth}" }
                } ?: ""
            }
        }
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
                    contentDescription =
                        if (temperature) "Recorded temperatures. Exact values are available below."
                        else "Cycle lengths. Every cycle and exclusion is listed below."
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
        Text("Your recorded rhythm", style = MaterialTheme.typography.titleLarge)
        Text("${d.getInt("sample_size")}", style = MaterialTheme.typography.displayLarge)
        Muted("Completed cycles used for estimates")
        val cycles =
            d.rows("cycles")
                .filter { !it.isNull("length") && it.isNull("excluded_reason") }
                .takeLast(12)
        RecordChart(cycles.map { it.getString("start") to it.getDouble("length") })
        Muted(d.getString("history_note"))
        TextButton(onClick = { history = !history }) {
            Text(if (history) "Hide cycle history" else "Show cycle history")
        }
        if (history)
            d.rows("cycles").asReversed().take(limit).forEach { cycle ->
                Text(
                    "${pretty(cycle.getString("start"))} · ${if (cycle.isNull("length")) "Current" else "${cycle.getInt("length")} days"} · ${cycle.getInt("bleeding_days")} bleeding",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!cycle.isNull("excluded_reason"))
                    Muted("Excluded: ${cycle.getString("excluded_reason")}")
            }
        if (history && d.rows("cycles").size > limit)
            TextButton(onClick = { limit += 30 }) { Text("More cycles") }
    }
    SectionTitle("How you feel · 180 days")
    if (d.rows("symptoms").isEmpty())
        Panel {
            Muted(
                "Record how you feel to start seeing patterns. Missing days are never counted as symptom-free."
            )
        }
    d.rows("symptoms").take(20).forEach { row ->
        Panel {
            Text(row.getString("name"), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { row.getInt("count").toFloat() / maxOf(1, row.getInt("logged_days")) },
                color = SevenColors.time,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${row.getInt("count")} of ${row.getInt("logged_days")} logged days")
            Muted(
                "Average when present ${"%.1f".format(row.getDouble("average"))}/3 · ${row.getInt("preperiod_count")} within 7 days before a recorded period"
            )
        }
    }
    val temperatures = d.rows("temperatures").takeLast(90)
    if (temperatures.isNotEmpty())
        Panel {
            Text(
                "Recorded temperature · ${BloomTemperature.unit}",
                style = MaterialTheme.typography.titleLarge,
            )
            RecordChart(
                temperatures.map {
                    it.getString("day") to BloomTemperature.display(it.getDouble("value"))
                },
                true,
            )
            Muted(
                "Latest 90 measurements, in entry order. Lines connect observations; they do not confirm ovulation."
            )
            var values by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { values = !values }) {
                Text(if (values) "Hide measurements" else "Show measurements")
            }
            if (values)
                temperatures.asReversed().forEach {
                    Text(
                        "${pretty(it.getString("day"))}${if (it.isNull("at")) " · daily summary" else " · ${timeLabel(it.getLong("at"))}"} · ${BloomTemperature.text(it.getDouble("value"))}"
                    )
                }
        }
    SectionTitle("Estimate exclusions")
    Muted(
        "Pregnancy and dated hormonal plans are excluded automatically. Add an interval for missing records or other context; observations stay intact."
    )
    OutlinedButton(onClick = { edit("exclusion", null) }) { Text("Exclude an interval") }
    a.rows("exclusions").forEach { row ->
        RecordCard(
            row.getString("reason"),
            "${pretty(row.getString("start"))} – ${if (row.isNull("end")) "Ongoing" else pretty(row.getString("end"))}",
            { edit("exclusion", row) },
            { remove("exclusion:${row.getString("id")}") },
        )
    }
    SectionTitle("Reports & selected sharing")
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
        DateField("From", from) {
            from = it
            changed()
        }
        DateField("Through", to) {
            to = it
            changed()
        }
        Check("Include sex/contact", contacts) {
            contacts = it
            changed()
        }
        Check("Include private notes", notes) {
            notes = it
            changed()
        }
        Button(enabled = !busy, onClick = { model.buildReport(from, to, contacts, notes) }) {
            Text("Preview report")
        }
        Muted(
            "Review what leaves your device. Family purchases never grant access to these records."
        )
        if (preview != null) {
            Text("Preview ready", style = MaterialTheme.typography.titleMedium)
            var show by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { show = !show }) {
                Text(if (show) "Hide report text" else "Read report text")
            }
            if (show) Text(preview!!, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pdf" to "PDF", "txt" to "text").forEach { (format, label) ->
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = { model.shareReport(format) },
                    ) {
                        Text("Share $label")
                    }
                }
                listOf("pdf" to "PDF", "txt" to "text").forEach { (format, label) ->
                    TextButton(
                        enabled = !busy,
                        onClick = { model.exportReport(format) },
                    ) {
                        Text("Save $label")
                    }
                }
            }
            Muted(
                "Only this reviewed report is shared. The person who receives it gets a copy, not access to your journal."
            )
        }
    }
}
