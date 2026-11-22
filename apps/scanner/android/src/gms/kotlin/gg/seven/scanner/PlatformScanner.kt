package gg.seven.scanner

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import gg.seven.scanner.core.scanLimits

/**
 * Personal and store: Google's ML Kit document scanner (Google Play services, a documented
 * platform exception like Play Billing), VisionKit's closest counterpart: edge detection,
 * perspective correction and cleanup, all on the device. Where Play services or the scanner
 * module is unavailable, the open-source CameraX capture takes over. [pageLimit] is how many pages
 * the target document can still take, so a capture never outgrows it.
 */
@Composable
fun rememberPlatformScanner(
    pageLimit: () -> Int,
    onPages: (List<Uri>) -> Unit,
    onCamera: () -> Unit,
): () -> Unit {
    val activity = LocalActivity.current
    val pages = rememberUpdatedState(onPages)
    val camera = rememberUpdatedState(onCamera)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) pages.value(uris)
        }
    return {
        val options =
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(pageLimit().coerceIn(1, scanLimits().maxPages.toInt()))
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        if (activity == null) camera.value()
        else {
            val client = GmsDocumentScanning.getClient(options)
            val modules = ModuleInstall.getClient(activity)
            // Until Play services has the scanner module, scan with CameraX now and ask for the
            // module in the background, so a missing download never blocks scanning.
            modules
                .areModulesAvailable(client)
                .addOnSuccessListener { status ->
                    if (status.areModulesAvailable())
                        client
                            .getStartScanIntent(activity)
                            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                            .addOnFailureListener { camera.value() }
                    else {
                        modules.deferredInstall(client)
                        camera.value()
                    }
                }
                .addOnFailureListener { camera.value() }
        }
    }
}
