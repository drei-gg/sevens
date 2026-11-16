@file:androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)

package gg.seven.qr

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import gg.seven.qr.core.ScannedCode
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/*
 * The live camera scanner (SPEC §2), the counterpart of iOS ScannerView and its coordinator:
 * CameraX preview and analysis, zxing-cpp reading every frame, ScanDebouncer deciding what is
 * delivered. Several codes in view are outlined and the person taps one. Pinch to zoom is
 * CameraX's own gesture on the preview.
 */

enum class ScannerAvailability { AVAILABLE, UNSUPPORTED, DENIED, NOT_ASKED }

fun scannerAvailability(context: Context, asked: Boolean): ScannerAvailability {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return ScannerAvailability.UNSUPPORTED
    val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    return when {
        granted -> ScannerAvailability.AVAILABLE
        asked -> ScannerAvailability.DENIED
        else -> ScannerAvailability.NOT_ASKED
    }
}

/** What the analyzer saw in one frame, still in the image's own coordinates. */
private class Frame(val codes: List<ScannedCode>, val corners: List<List<PointF>>, val source: OutputTransform)

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScanner(
    active: Boolean,
    torchOn: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    onCode: (ScannedCode) -> Unit,
    onMultipleChange: (Boolean) -> Unit,
    onStartFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            isTapToFocusEnabled = false
            isPinchToZoomEnabled = true
        }
    }
    val debouncer = remember { ScanDebouncer() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    /** Codes in view and their outlines in the preview's coordinates. */
    var outlines by remember { mutableStateOf<List<Located>>(emptyList()) }
    var wake by remember { mutableStateOf<Long?>(null) }
    var wasMultiple by remember { mutableStateOf(false) }
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnMultiple by rememberUpdatedState(onMultipleChange)

    fun setMultiple(value: Boolean) {
        if (value == wasMultiple) return
        wasMultiple = value
        currentOnMultiple(value)
    }

    fun finish(released: ScannedCode?) {
        if (released != null) {
            outlines = emptyList()
            setMultiple(false)
            wake = null
            currentOnCode(released)
            return
        }
        wake = debouncer.nextWake
    }

    fun deliverFrame(frame: Frame) {
        if (debouncer.isPaused) return
        val view = previewView
        val target = view?.outputTransform
        outlines =
            if (target == null) emptyList()
            else {
                val transform = CoordinateTransform(frame.source, target)
                frame.codes.mapIndexed { index, code ->
                    val points = frame.corners[index].flatMap { listOf(it.x, it.y) }.toFloatArray()
                    transform.mapPoints(points)
                    Located(code, points.toList().chunked(2) { PointF(it[0], it[1]) })
                }
            }
        val released = debouncer.observe(frame.codes, SystemClock.uptimeMillis())
        setMultiple(debouncer.hasMultiple)
        finish(released)
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    // Active: analyse frames; inactive (a sheet is up, the app is away): stop, and pause the timing.
    LaunchedEffect(active) {
        if (active) {
            // Bound only while scanning, so the camera (and its privacy dot) is off under a sheet.
            controller.bindToLifecycle(lifecycleOwner)
            controller.initializationFuture.addListener(
                { onTorchAvailable(controller.cameraInfo?.hasFlashUnit() == true) },
                ContextCompat.getMainExecutor(context),
            )
            debouncer.resume(SystemClock.uptimeMillis())
            val reader = ScanDecoding.reader(stills = false)
            val main = ContextCompat.getMainExecutor(context)
            val factory = ImageProxyTransformFactory().apply {
                isUsingRotationDegrees = true
                isUsingCropRect = true
            }
            runCatching {
                    controller.setImageAnalysisAnalyzer(executor) { image ->
                        try {
                            val results = runCatching { reader.read(image) }.getOrDefault(emptyList())
                            val source = factory.getOutputTransform(image)
                            val frame =
                                Frame(
                                    results.map(ScanDecoding::code),
                                    results.map { r ->
                                        // zxing-cpp reports positions in the rotated, cropped image.
                                        listOf(r.position.topLeft, r.position.topRight, r.position.bottomRight, r.position.bottomLeft)
                                            .map { PointF(it.x.toFloat(), it.y.toFloat()) }
                                    },
                                    source,
                                )
                            main.execute { deliverFrame(frame) }
                        } finally {
                            image.close()
                        }
                    }
                }
                .onFailure { onStartFailure() }
        } else {
            debouncer.pause()
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            outlines = emptyList()
            setMultiple(false)
            wake = null
        }
    }

    // The camera reports changes, not the passing of time: wake the debouncer when its dwell ends.
    LaunchedEffect(wake) {
        val at = wake ?: return@LaunchedEffect
        delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
        finish(debouncer.tick(SystemClock.uptimeMillis()))
    }

    LaunchedEffect(torchOn) { runCatching { controller.enableTorch(torchOn) } }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                    contentDescription = "Camera viewfinder for scanning codes"
                    // A tap on an outlined code picks it; pinches reach CameraX's zoom.
                    val taps =
                        GestureDetector(
                            ctx,
                            object : GestureDetector.SimpleOnGestureListener() {
                                override fun onSingleTapUp(e: MotionEvent): Boolean {
                                    val slop = 24 * resources.displayMetrics.density
                                    val hit = outlines.firstOrNull { located ->
                                        val box = located.bounds
                                        e.x >= box.left - slop && e.x <= box.right + slop && e.y >= box.top - slop && e.y <= box.bottom + slop
                                    } ?: return false
                                    finish(debouncer.tap(hit.code))
                                    return true
                                }
                            },
                        )
                    setOnTouchListener { view, event ->
                        taps.onTouchEvent(event)
                        view.onTouchEvent(event)
                    }
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Camera viewfinder for scanning codes" },
        )
        // Several codes in view: outline each, so the person can tap the one they want.
        if (outlines.size > 1)
            Canvas(Modifier.fillMaxSize()) {
                outlines.forEach { located ->
                    val path = Path()
                    located.corners.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    path.close()
                    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(width = 7.dp.toPx(), join = StrokeJoin.Round))
                    drawPath(path, Color.White, style = Stroke(width = 4.dp.toPx(), join = StrokeJoin.Round))
                    drawPath(path, Color.White.copy(alpha = 0.18f))
                }
            }
    }
}
