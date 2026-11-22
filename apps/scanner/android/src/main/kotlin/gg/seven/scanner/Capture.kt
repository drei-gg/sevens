package gg.seven.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import gg.seven.kit.Space
import gg.seven.kit.numeric
import gg.seven.scanner.core.scanLimits
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * Whether this device has any camera to scan with (iOS
 * `VNDocumentCameraViewController.isSupported`).
 */
fun cameraAvailable(context: android.content.Context) =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * The open-source scanner (VisionKit's counterpart): a full-bleed CameraX viewfinder, a shutter in
 * thumb reach, and Save once pages are taken. No edge detection or perspective correction: pages
 * are saved as photographed and can be rotated and adjusted in the page editor. Up to 50 pages.
 */
@Composable
fun CameraCapture(
    onSave: (List<Uri>, cleanUp: () -> Unit) -> Unit,
    onClose: () -> Unit,
    onImport: (() -> Unit)?,
    busy: Boolean = false,
    progress: String = "",
    limit: Int = scanLimits().maxPages.toInt(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val folderPath = rememberSaveable {
        File(context.cacheDir, "ScannerCapture/${UUID.randomUUID()}").absolutePath
    }
    val folder = remember(folderPath) { File(folderPath).apply { mkdirs() } }
    // An empty draft folder is not worth keeping once the screen goes (it is recreated on return).
    DisposableEffect(folderPath) { onDispose { if (folder.list().isNullOrEmpty()) folder.delete() } }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            asked = true
        }
    LaunchedEffect(Unit) { if (!granted && !asked) permission.launch(Manifest.permission.CAMERA) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }
    // Restore captured pages from this draft's own directory after rotation/process recreation.
    val pages =
        remember(folderPath) {
            mutableStateListOf<File>().apply {
                addAll(
                    folder
                        .listFiles()
                        .orEmpty()
                        .filter { it.extension == "jpg" && it.length() > 0 }
                        .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }
                )
            }
        }
    var taking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun close() {
        folder.deleteRecursively()
        onClose()
    }
    BackHandler {
        if (!taking && !busy) {
            if (pages.isEmpty()) close() else discard = true
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("scanner.camera")) {
        if (granted) {
            val owner = LocalLifecycleOwner.current
            var request by remember { mutableStateOf<SurfaceRequest?>(null) }
            val capture = remember {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
            }
            LaunchedEffect(owner) {
                withContext(Dispatchers.Main.immediate) {
                    val provider = ProcessCameraProvider.awaitInstance(context)
                    val preview =
                        Preview.Builder().build().apply { setSurfaceProvider { request = it } }
                    try {
                        provider.bindToLifecycle(
                            owner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                        )
                        awaitCancellation()
                    } catch (e: IllegalArgumentException) {
                        failure =
                            "The camera couldn’t start. You can import images or PDFs instead."
                    } finally {
                        provider.unbind(preview, capture)
                    }
                }
            }
            request?.let {
                CameraXViewfinder(
                    surfaceRequest = it,
                    modifier = Modifier.fillMaxSize().testTag("scanner.viewfinder"),
                )
            }
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(Space.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.ms),
            ) {
                Text(
                    if (busy) progress
                    else
                        failure
                            ?: if (pages.isEmpty()) "Hold the page flat and fill the frame."
                            else "${pageCount(pages.size)} · up to $limit",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.numeric(),
                    textAlign = TextAlign.Center,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TextButton(
                            onClick = { if (pages.isEmpty()) close() else discard = true },
                            enabled = !taking && !busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text("Cancel")
                        }
                    }
                    FilledIconButton(
                        onClick = {
                            if (taking || busy || pages.size >= limit) return@FilledIconButton
                            taking = true
                            val file = File(folder, "${pages.size + 1}.jpg")
                            capture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(
                                        output: ImageCapture.OutputFileResults
                                    ) {
                                        pages += file
                                        taking = false
                                        view.confirm()
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        file.delete()
                                        taking = false
                                        failure = "That page couldn’t be taken. Try again."
                                        view.reject()
                                    }
                                },
                            )
                        },
                        enabled = !taking && !busy && pages.size < limit,
                        modifier = Modifier.size(76.dp).testTag("scanner.shutter"),
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, "Take page", Modifier.size(32.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Button(
                            onClick = {
                                onSave(pages.map(Uri::fromFile)) { folder.deleteRecursively() }
                            },
                            enabled = pages.isNotEmpty() && !taking && !busy,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black,
                                ),
                            modifier = Modifier.testTag("scanner.capture.save"),
                        ) {
                            Text(
                                if (pages.isEmpty()) "Save" else "Save ${pages.size}",
                                style = LocalTextStyle.current.numeric(),
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(Space.l).widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Text(
                    "Camera access is off",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Scanning needs the camera. Turn it on in Settings, or import images and PDFs instead.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (asked)
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                            )
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                    ) {
                        Text("Open settings")
                    }
                onImport?.let {
                    OutlinedButton(
                        onClick = it,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Text("Import instead")
                    }
                }
            }
            IconButton(
                onClick = ::close,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Space.s),
            ) {
                Icon(Icons.Outlined.Close, "Close", tint = Color.White)
            }
        }
    }
    LaunchedEffect(Unit) {
        // Permission granted from Settings while the screen was open.
        granted =
            granted ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Discard ${pageCount(pages.size)}?") },
            text = { Text("The pages you took here are not saved yet.") },
            confirmButton = {
                TextButton(onClick = ::close) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep scanning") } },
        )
}
