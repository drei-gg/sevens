package gg.seven.fast

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import gg.seven.fast.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Vico owns chart drawing, axes, scroll and zoom. The same data is available as an accessible
 * table.
 */
@Composable
internal fun DailyChart(days: List<Pair<LocalDate, Double?>>, unit: String, color: Color) {
    val producer = remember { CartesianChartModelProducer() }
    var table by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(days) {
        producer.runTransaction {
            columnSeries {
                series(x = days.map { it.first.toEpochDay() }, y = days.map { it.second ?: 0.0 })
            }
        }
    }
    val formatter = remember {
        CartesianValueFormatter { _, value, _ ->
            LocalDate.ofEpochDay(value.toLong()).let { "${it.monthValue}/${it.dayOfMonth}" }
        }
    }
    ProvideVicoTheme(rememberM3VicoTheme(columnCartesianLayerColors = listOf(color))) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(valueFormatter = formatter, guideline = null),
                ),
            modelProducer = producer,
            zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
            animationSpec = null,
            animateIn = false,
            modifier =
                Modifier.fillMaxWidth().height(220.dp).clearAndSetSemantics {
                    contentDescription =
                        "Daily $unit chart, ${days.first().first} to ${days.last().first}. Use Show daily values for every value."
                },
        )
    }
    Text(
        "${days.first().first} — ${days.last().first} · $unit. Scroll or pinch to inspect.",
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { table = !table }) {
        Text(if (table) "Hide daily values" else "Show daily values")
    }
    if (table)
        days.reversed().forEach { (day, value) ->
            Text(
                "$day · ${value?.let { "${it.number()} $unit" } ?: "No entries"}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
}

@Composable
internal fun MeasurementChart(entries: List<MetricEntry>, settings: TrackerSettings) {
    val producer = remember { CartesianChartModelProducer() }
    val formatter = remember {
        CartesianValueFormatter { _, value, _ ->
            Instant.ofEpochMilli((value * 86_400_000).toLong())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("M/d"))
        }
    }
    val points =
        remember(entries, settings) {
            entries
                .sortedBy { it.date }
                .map { it.date.millis() / 86_400_000 to Units.display(it.value, it.kind, settings) }
        }
    LaunchedEffect(points) {
        producer.runTransaction {
            lineSeries { series(x = points.map { it.first }, y = points.map { it.second }) }
        }
    }
    val low = points.minOf { it.second }
    val high = points.maxOf { it.second }
    val padding = maxOf((high - low) * .1, .5)
    val color = MaterialTheme.colorScheme.primary
    val point =
        remember(color) {
            LineCartesianLayer.Point(ShapeComponent(fill(color), CorneredShape.Pill), 6f)
        }
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider =
                            LineCartesianLayer.LineProvider.series(
                                LineCartesianLayer.rememberLine(
                                    pointProvider = LineCartesianLayer.PointProvider.single(point)
                                )
                            ),
                        rangeProvider =
                            CartesianLayerRangeProvider.fixed(
                                minY = low - padding,
                                maxY = high + padding,
                            ),
                    ),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(valueFormatter = formatter, guideline = null),
                ),
            modelProducer = producer,
            zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
            animationSpec = null,
            animateIn = false,
            modifier =
                Modifier.fillMaxWidth().height(160.dp).clearAndSetSemantics {
                    contentDescription =
                        "Measurement chart. Values are listed below with their dates."
                },
        )
    }
}
