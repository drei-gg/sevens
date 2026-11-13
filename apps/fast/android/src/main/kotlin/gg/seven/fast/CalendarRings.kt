package gg.seven.fast

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import gg.seven.fast.core.*
import gg.seven.kit.SevenColors
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/** Swift day-ring geometry on the calendar library's native week layout. */
@Composable
internal fun CalendarRings(
    weeks: List<List<LocalDate>>,
    archive: FastArchive,
    now: Double,
    modifier: Modifier,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
    val surface = MaterialTheme.colorScheme.surface
    val time = SevenColors.time
    Canvas(modifier) {
        val column = size.width / 7
        val radius = 16.25.dp.toPx()
        val line = 3.5.dp.toPx()
        weeks.forEachIndexed { row, days ->
            val cy = row * 80.dp.toPx() + 22.dp.toPx()
            days.forEachIndexed { col, day ->
                val cx = (col + .5f) * column
                val center = Offset(cx, cy)
                drawCircle(track, radius, center, style = Stroke(line))
                DayArcs.pieces(day, archive, ZoneId.systemDefault(), now).forEach { arc ->
                    val planned = arc.kind == DayArc.Kind.planned
                    val color = time.copy(alpha = if (planned) .55f else 1f)
                    val stroke =
                        Stroke(
                            if (planned) line / 2 else line,
                            cap = StrokeCap.Round,
                            pathEffect =
                                if (planned)
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(3.dp.toPx(), 3.dp.toPx())
                                    )
                                else null,
                        )
                    val top = cy - radius
                    if (arc.fromPreviousDay)
                        drawLine(
                            color,
                            Offset(col * column, top),
                            Offset(cx, top),
                            strokeWidth = stroke.width,
                            pathEffect = stroke.pathEffect,
                            cap = StrokeCap.Round,
                        )
                    if (arc.intoNextDay)
                        drawLine(
                            color,
                            Offset(cx, top),
                            Offset((col + 1) * column, top),
                            strokeWidth = stroke.width,
                            pathEffect = stroke.pathEffect,
                            cap = StrokeCap.Round,
                        )
                    drawArc(
                        color,
                        (arc.from * 360 - 90).toFloat(),
                        maxOf(.5, (arc.to - arc.from) * 360).toFloat(),
                        false,
                        Offset(cx - radius, cy - radius),
                        Size(radius * 2, radius * 2),
                        style = stroke,
                    )
                    if (arc.kind == DayArc.Kind.running && !arc.intoNextDay) {
                        val theta = arc.to * 2 * Math.PI - Math.PI / 2
                        val point =
                            Offset(
                                cx + radius * cos(theta).toFloat(),
                                cy + radius * sin(theta).toFloat(),
                            )
                        drawCircle(surface, 3.5.dp.toPx(), point)
                        drawCircle(color, 3.5.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}
