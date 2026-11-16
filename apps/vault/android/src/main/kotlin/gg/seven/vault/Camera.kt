package gg.seven.vault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import gg.seven.kit.EmptyState
import gg.seven.kit.Space
import java.io.File
import java.util.UUID

/**
 * iOS `VaultCamera` (UIImagePickerController) on Android: CameraX inside Vault, so the photo is
 * written only to Vault's own temporary area and never to MediaStore or another camera app's
 * gallery. The capture is encrypted into the vault and the temporary file removed.
 */
@Composable
fun CameraScreen(model: VaultModel, onCaptured: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.external = false
            granted = it
            asked = true
        }
    LaunchedEffect(Unit) {
        if (!granted) {
            model.external = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            if (granted) Viewfinder(model, onCaptured, onClose)
            else
                Column(Modifier.fillMaxSize().padding(Space.l), verticalArrangement = Arrangement.Center) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.background) {
                        Column {
                            EmptyState(
                                "Camera access is off",
                                "Vault uses the camera only to take a photo straight into this vault. Files, photos and notes still work without it.",
                                icon = Icons.Outlined.PhotoCamera,
                                action =
                                    if (asked) "Open settings" to {
                                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                                    }
                                    else null,
                            )
                            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = Space.m)) { Text("Close") }
                        }
                    }
                }
        }
    }
}

@Composable
private fun Viewfinder(model: VaultModel, onCaptured: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var taking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val view = remember { PreviewView(context) }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                    val provider = future.get()
                    val preview = CameraPreview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }
                .onFailure { failure = "The camera could not start. Close and try again." }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ view }, Modifier.fillMaxSize())
        IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(Space.s).align(Alignment.TopStart)) {
            Icon(Icons.Outlined.Close, "Close camera", tint = Color.White)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.l), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(failure ?: "The photo goes straight into this vault, not your gallery.", color = Color.White, style = MaterialTheme.typography.bodySmall)
            FilledIconButton(
                onClick = {
                    if (taking) return@FilledIconButton
                    taking = true
                    val folder = File(model.repository.temporary, "capture").apply { mkdirs() }
                    val file = File(folder, "Photo-" + UUID.randomUUID().upper().take(8) + ".jpg")
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onCaptured(file)

                            override fun onError(exception: ImageCaptureException) {
                                file.delete()
                                taking = false
                                failure = "The photo could not be taken. Try again."
                            }
                        },
                    )
                },
                enabled = !taking && failure == null,
                shape = CircleShape,
                modifier = Modifier.size(76.dp),
            ) {
                Icon(Icons.Outlined.PhotoCamera, "Take photo", Modifier.size(32.dp))
            }
        }
    }
}
