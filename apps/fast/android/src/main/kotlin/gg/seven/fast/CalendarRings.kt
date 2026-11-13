package gg.seven.fast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import gg.seven.fast.core.*
import gg.seven.kit.SevenColors
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/**
 * The calendar's 24-hour day rings: midnight at the top, noon at the bottom, clockwise. Each fast
 * is one continuous stroke across its week row, as the core's `weekStrokes` lays it out (the same
 * spec iOS draws): through midnight along a line into the next ring, off a row's edge and back in
 * on the next, dashed when planned, an open dot at a running fast's end, and a small gap where one
 * fast hands over to the next at the same midnight. Geometry is iOS's LinkedDayRings: 36 dp
 * rings, 3.5 dp line.
 */
@Composable
internal fun CalendarRings(
    weeks: List<List<LocalDate>>,
    archive: FastArchive,
    now: Double,
    modifier: Modifier,
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val time = SevenColors.time
    val zone = ZoneId.systemDefault()
    val rows = weeks.map { week -> weekStrokes(week.map { DayArcs.pieces(it, archive, zone, now) }) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            weeks.forEachIndexed { row, days ->
                val g = Geometry(this, row)
                days.indices.forEach { drawCircle(track, g.radius, g.center(it), style = Stroke(g.line)) }
            }
        }
        // Its own layer, so a handover gap clears earlier strokes but never the rings beneath.
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            rows.forEachIndexed { row, strokes ->
                val g = Geometry(this, row)
                strokes.forEach { stroke ->
                    stroke.gaps.forEach { gap ->
                        drawPath(
                            Path().apply { arcTo(g.ring(gap.day.toInt()), g.angle(gap.from), g.sweep(gap.from, gap.to), true) },
                            Color.Black,
                            style = Stroke(g.line + 4.dp.toPx(), cap = StrokeCap.Butt),
                            blendMode = BlendMode.Clear,
                        )
                    }
                    val planned = stroke.kind == DayArcKind.PLANNED
                    drawPath(
                        g.path(stroke.steps),
                        time,
                        style =
                            if (planned)
                                Stroke(
                                    g.line / 2,
                                    cap = StrokeCap.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                                )
                            else Stroke(g.line, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    val day = stroke.endDotDay
                    val position = stroke.endDotPosition
                    if (day != null && position != null) {
                        val end = g.point(day.toInt(), position)
                        drawCircle(surface, g.line * 1.2f, end)
                        drawCircle(time, g.line * 1.2f, end, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
    }
}

private class Geometry(scope: DrawScope, row: Int) {
    val column = scope.size.width / 7
    val line = with(scope) { 3.5.dp.toPx() }
    val radius = with(scope) { (36.dp.toPx() - line) / 2 }
    private val width = scope.size.width
    private val cy = with(scope) { row * 80.dp.toPx() + 4.dp.toPx() + 18.dp.toPx() }
    private val top = cy - radius

    fun center(day: Int) = Offset(column * (day + .5f), cy)

    fun angle(position: Double) = (position * 360 - 90).toFloat()

    fun sweep(from: Double, to: Double) = ((to - from) * 360).toFloat()

    fun ring(day: Int) = Rect(center(day), radius)

    fun point(day: Int, position: Double): Offset {
        val a = Math.toRadians(angle(position).toDouble())
        val c = center(day)
        return Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
    }

    fun path(steps: List<StrokeStep>) =
        Path().apply {
            steps.forEach { step ->
                when (step) {
                    is StrokeStep.StartOnRing -> point(step.day.toInt(), step.position).let { moveTo(it.x, it.y) }
                    is StrokeStep.StartAtLeadingEdge -> moveTo(0f, top)
                    is StrokeStep.LineToTop -> lineTo(step.day?.let { center(it.toInt()).x } ?: width, top)
                    is StrokeStep.Arc -> {
                        // A whole day is a full turn; Path.arcTo draws nothing for 360°, so in halves.
                        val sweep = sweep(step.from, step.to)
                        val ring = ring(step.day.toInt())
                        if (sweep >= 359.9f) {
                            arcTo(ring, angle(step.from), sweep / 2, false)
                            arcTo(ring, angle(step.from) + sweep / 2, sweep / 2, false)
                        } else arcTo(ring, angle(step.from), sweep, false)
                    }
                }
            }
        }
}
