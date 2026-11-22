package gg.seven.scanner

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState

/**
 * Generic: scanning is the open-source CameraX capture ([CameraCapture]). Personal and store use
 * Google's ML Kit document scanner when Play services has it (src/gms).
 */
@Composable
fun rememberPlatformScanner(
    pageLimit: () -> Int,
    onPages: (List<Uri>) -> Unit,
    onCamera: () -> Unit,
): () -> Unit {
    val camera = rememberUpdatedState(onCamera)
    return { camera.value() }
}
