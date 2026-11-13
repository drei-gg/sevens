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
import gg.seven.fast.core.*
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
                        simulation.accelerometer(xy.first, xy.second, false)
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
            (level ?: 0.0).coerceAtLeast(0.0).toFloat(),
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
                    // The core decides the layers (laps past the goal alternate tones) and each
                    // surface's outline in dp; this only paints them.
                    val state = simulation.state()
                    waterLayers(fill.toDouble()).forEach { layer ->
                        val path =
                            outlinePath(
                                liquidOutline(
                                    (size.width / density).toDouble(),
                                    (size.height / density).toDouble(),
                                    layer.fraction,
                                    state,
                                    layer.wave,
                                    if (layer.lifted) 5.0 else 0.0,
                                ),
                                size,
                                density,
                            )
                        when (layer.tone) {
                            WaterTone.CREST -> drawPath(path, Color.White.copy(alpha = .55f))
                            WaterTone.SHALLOW -> drawPath(path, water.copy(alpha = .85f))
                            WaterTone.LIGHT -> {
                                drawPath(path, surface)
                                drawPath(path, water.copy(alpha = .45f))
                            }
                            WaterTone.DEEP -> drawPath(path, water)
                        }
                    }
                }
            }
            drawCircle(water, style = Stroke(3.dp.toPx()))
        }
        if (level == null)
            Icon(Icons.Filled.WaterDrop, null, tint = water, modifier = Modifier.fillMaxSize(.48f))
    }
}

private fun outlinePath(outline: LiquidOutline, size: Size, density: Float): Path {
    val path = Path()
    fun Point.offset() = Offset((x * density).toFloat(), (y * density).toFloat())
    when (outline) {
        is LiquidOutline.Empty -> Unit
        is LiquidOutline.Full -> path.addRect(Rect(Offset.Zero, size))
        is LiquidOutline.Shape -> {
            outline.start.offset().let { path.moveTo(it.x, it.y) }
            outline.surfaceStart.offset().let { path.lineTo(it.x, it.y) }
            outline.curves.forEach { c ->
                val (one, two, to) = Triple(c.control1.offset(), c.control2.offset(), c.to.offset())
                path.cubicTo(one.x, one.y, two.x, two.y, to.x, to.y)
            }
            outline.end.offset().let { path.lineTo(it.x, it.y) }
            path.close()
        }
    }
    return path
}
