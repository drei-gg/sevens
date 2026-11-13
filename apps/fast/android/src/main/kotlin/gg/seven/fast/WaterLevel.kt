package gg.seven.fast

import android.content.Context
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import gg.seven.fast.core.LiquidGeometry
import gg.seven.fast.core.LiquidSimulation
import gg.seven.kit.SevenColors
import kotlin.math.*
import kotlinx.coroutines.isActive

/**
 * Swift WaterLevel/LiquidShape port. Decorative; the adjacent amount and goal provide semantics.
 * Sensors and frame callbacks exist only while visible/resumed with motion enabled.
 */
@Composable
internal fun WaterLevel(level: Double?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    fun animationsEnabled() =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    var motionEnabled by remember { mutableStateOf(animationsEnabled()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    motionEnabled = animationsEnabled()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    var visible by remember { mutableStateOf(false) }
    val moving =
        visible &&
            lifecycle.isAtLeast(Lifecycle.State.RESUMED) &&
            motionEnabled &&
            level != null &&
            level > 0
    val simulation = remember(moving) { LiquidSimulation() }
    var frame by remember { mutableLongStateOf(0) }
    val rotation = remember(configuration) { view.display?.rotation ?: Surface.ROTATION_0 }
    DisposableEffect(moving, rotation) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener =
            object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_GYROSCOPE)
                        simulation.gyroscope(event.values[2].toDouble())
                    else {
                        val x = event.values[0].toDouble()
                        val y = event.values[1].toDouble()
                        // Android sensors stay in natural device coordinates when the screen
                        // rotates.
                        val xy =
                            when (rotation) {
                                Surface.ROTATION_90 -> -y to x
                                Surface.ROTATION_180 -> -x to -y
                                Surface.ROTATION_270 -> y to -x
                                else -> x to y
                            }
                        simulation.accelerometer(xy.first, xy.second)
                    }
                }
            }
        if (moving) {
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                manager.registerListener(listener, it, 50_000)
            }
            manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                manager.registerListener(listener, it, 50_000)
            }
        }
        onDispose { manager.unregisterListener(listener) }
    }
    LaunchedEffect(moving, simulation) {
        if (moving) {
            var previous = 0L
            while (isActive) withFrameNanos { now ->
                if (previous == 0L) previous = now
                else if (now - previous >= 33_000_000L) {
                    simulation.step((now - previous) / 1_000_000_000.0)
                    previous = now
                    frame++
                }
            }
        }
    }
    val fill by
        animateFloatAsState(
            (level ?: 0.0).coerceIn(0.0, 2.0).toFloat(),
            tween(if (motionEnabled) 400 else 0),
            label = "Water amount",
        )
    val water = SevenColors.water
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .aspectRatio(1f)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                visible =
                    bounds.width > 0 &&
                        bounds.height > 0 &&
                        bounds.bottom > 0 &&
                        bounds.top < view.height
            }
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            frame // Draw invalidation only; do not recompose the screen at 30 fps.
            drawCircle(surface)
            val circle = Path().apply { addOval(Rect(Offset.Zero, size)) }
            clipPath(circle) {
                if (level != null && fill > 0) {
                    val base = min(1f, fill).toDouble()
                    val over = (fill - 1).coerceIn(0f, 1f).toDouble()
                    drawPath(
                        liquidPath(size, base, simulation, 0, 5.dp.toPx(), density),
                        Color.White.copy(alpha = .55f),
                    )
                    drawPath(
                        liquidPath(size, base, simulation, 1, 0f, density),
                        water.copy(alpha = if (over > 0) .45f else .85f),
                    )
                    if (over > 0)
                        drawPath(liquidPath(size, over, simulation, 2, 0f, density), water)
                }
            }
            drawCircle(water, style = Stroke(3.dp.toPx()))
        }
        if (level == null)
            Icon(Icons.Filled.WaterDrop, null, tint = water, modifier = Modifier.fillMaxSize(.48f))
    }
}

private fun liquidPath(
    size: Size,
    fraction: Double,
    state: LiquidSimulation,
    layer: Int,
    lift: Float,
    density: Float,
): Path {
    val path = Path()
    if (fraction <= 0) return path
    if (fraction >= 1) {
        path.addRect(Rect(Offset.Zero, size))
        return path
    }
    val radius = max(size.width, size.height).toDouble()
    val surface = LiquidGeometry.circleSurfaceOffset(fraction) * min(size.width, size.height) / 2
    val damping = sin(PI * fraction)
    val cosine = cos(state.angle)
    val sine = sin(state.angle)
    val phase = state.phase + layer
    val amplitude = (2 + state.ripple) * density * damping * (if (layer == 0) .65 else 1.0)
    val baseline = surface - lift * damping
    val frequency = PI / radius
    fun point(x: Double, y: Double) =
        Offset(
            (size.width / 2 + x * cosine - y * sine).toFloat(),
            (size.height / 2 + x * sine + y * cosine).toFloat(),
        )
    fun wave(x: Double) = baseline + sin((x + radius) * frequency + phase) * amplitude
    fun slope(x: Double) = cos((x + radius) * frequency + phase) * amplitude * frequency
    val bottom = point(-radius, radius)
    path.moveTo(bottom.x, bottom.y)
    val first = point(-radius, wave(-radius))
    path.lineTo(first.x, first.y)
    val width = 2 * radius / 16
    repeat(16) { i ->
        val x = -radius + i * width
        val next = x + width
        val c1 = point(x + width / 3, wave(x) + slope(x) * width / 3)
        val c2 = point(next - width / 3, wave(next) - slope(next) * width / 3)
        val end = point(next, wave(next))
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    }
    val last = point(radius, radius)
    path.lineTo(last.x, last.y)
    path.close()
    return path
}
