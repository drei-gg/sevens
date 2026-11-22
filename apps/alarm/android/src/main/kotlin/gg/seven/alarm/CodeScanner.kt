@file:OptIn(ExperimentalMaterial3Api::class)

package gg.seven.alarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import gg.seven.kit.EmptyState
import gg.seven.kit.Space
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads one QR code or barcode with the camera (iOS CodeScanner over DataScanner): CameraX for the
 * camera, ZXing to decode, on every flavor. The contents are handed back as data, never opened.
 */
@Composable
fun CodeScanner(onClose: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.scanner_title)) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.common_cancel)) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    failure != null -> EmptyState(stringResource(R.string.scanner_camera_failed), failure.orEmpty(), icon = Icons.Outlined.NoPhotography)
                    granted && hasCamera -> CameraFeed(onCode = onCode, onFailure = { failure = it })
                    !granted && !asked -> Unit
                    else ->
                        EmptyState(
                            stringResource(R.string.scanner_unavailable),
                            stringResource(R.string.scanner_unavailable_body),
                            icon = Icons.Outlined.NoPhotography,
                            action =
                                stringResource(R.string.common_open_settings) to {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                                },
                        )
                }
            }
        }
    }
}

@Composable
private fun CameraFeed(onCode: (String) -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val consumed = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val latest by rememberUpdatedState(onCode)
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                try {
                    val camera = future.get().also { provider = it }
                    val shown = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to BarcodeFormat.entries, DecodeHintType.TRY_HARDER to true)) }
                    analysis.setAnalyzer(executor) { image ->
                        val text = image.use { decode(reader, it) }
                        // One code per scan; anything over 4 KB isn't a mission code.
                        if (text != null && text.toByteArray().size <= 4096 && consumed.compareAndSet(false, true))
                            ContextCompat.getMainExecutor(context).execute {
                                Haptics.confirm(view)
                                latest(text)
                            }
                    }
                    camera.unbindAll()
                    camera.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
                } catch (e: Exception) {
                    onFailure(e.message ?: res.getString(R.string.scanner_did_not_start))
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        // The viewfinder: white with a dark keyline so it reads on any picture (polish guide §3.1).
        Box(Modifier.size(240.dp).border(5.dp, Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.large).padding(2.dp).border(3.dp, Color.White, MaterialTheme.shapes.large).clearAndSetSemantics {})
        Surface(
            Modifier.align(Alignment.BottomCenter).padding(Space.l),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(stringResource(R.string.scanner_hint), Modifier.padding(horizontal = Space.m, vertical = Space.s), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Decodes the luminance plane of one camera frame, rotated or not; null when there is no code. */
private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val width = image.width
    val height = image.height
    val stride = plane.rowStride
    val bytes = ByteArray(width * height)
    for (row in 0 until height) {
        buffer.position(row * stride)
        buffer.get(bytes, row * width, width)
    }
    val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
        // Light-on-dark codes: try the frame inverted.
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
        } catch (_: Exception) {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
