@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package gg.seven.qr

import androidx.core.content.edit
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import gg.seven.kit.Space
import gg.seven.qr.core.Analysis
import gg.seven.qr.core.HistoryKind
import gg.seven.qr.core.Payload
import gg.seven.qr.core.ScannedCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The home screen (SPEC §2): the live scanner, the torch, photo and paste, and the way to Make,
 * History and Settings. Showing a result is not acting (D7): a scan only opens the result sheet.
 */

/** One row of the pick list shown when a photo or the clipboard holds several codes. */
data class ScanCandidate(val index: Int, val analysis: Analysis) {
    val typeName = QrText.typeName(analysis.payload.typeId)
    /** For links: the registrable domain, else the host. */
    val domain: String? = (analysis.payload as? Payload.Link)?.info?.let { it.registrableDomain ?: it.hostUnicode }?.takeIf { it.isNotEmpty() }
}

private fun cameraAsked(context: Context) = context.getSharedPreferences("qr-settings", Context.MODE_PRIVATE).getBoolean("cameraAsked", false)

private fun markCameraAsked(context: Context) = context.getSharedPreferences("qr-settings", Context.MODE_PRIVATE).edit { putBoolean("cameraAsked", true) }

@Composable
fun ScanScreen(model: QrModel, nav: QrNav) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateAsState()
    var availability by remember { mutableStateOf(scannerAvailability(context, cameraAsked(context))) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var multipleInView by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val result by nav.result.collectAsStateWithLifecycle()
    val candidates by nav.candidates.collectAsStateWithLifecycle()
    val pending by model.pendingCode.collectAsStateWithLifecycle()
    val sheetIsUp = result != null || candidates != null

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        markCameraAsked(context)
        availability = scannerAvailability(context, asked = true)
    }
    // Camera permission is asked when the scanner first opens, not before (SPEC §1).
    // Not over Before you buy on the first launch: wait until it has been answered.
    LaunchedEffect(Unit) {
        while (!model.store.beforeYouBuyShown) delay(300)
        if (availability == ScannerAvailability.NOT_ASKED) permission.launch(Manifest.permission.CAMERA)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { availability = scannerAvailability(context, cameraAsked(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { torchOn = false }
    LaunchedEffect(sheetIsUp) { if (sheetIsUp) torchOn = false }

    /** Analyses, saves to History (when it is on and allowed), and shows the sheet. */
    suspend fun present(code: ScannedCode) {
        if (nav.result.value != null || nav.candidates.value != null) return
        message = null
        val analysis = model.analysis(code)
        model.record(analysis, HistoryKind.SCANNED)
        if (nav.result.value != null || nav.candidates.value != null) return
        view.confirmHaptic()
        nav.showResult(analysis)
    }

    suspend fun handle(codes: List<ScannedCode>, empty: String) {
        message = null
        when (codes.size) {
            0 -> message = empty
            1 -> present(codes[0])
            else -> nav.candidates.value = codes.mapIndexed { i, code -> ScanCandidate(i, model.analysis(code)) }
        }
    }

    LaunchedEffect(pending) {
        val code = pending ?: return@LaunchedEffect
        // Cleared only once shown: a launch can recompose this screen away mid-analysis.
        present(code)
        model.pendingCode.value = null
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            message = null
            val codes = ImageScanner.scan(context, uri)
            working = false
            if (codes == null) message = "That photo couldn’t be read" else handle(codes, "No code found in this image")
        }
    }
    val onPhoto = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val onPaste: () -> Unit = { scope.launch { handle(PasteScanner.codes(context), "Nothing to scan on the clipboard") } }
    val showsCamera = availability == ScannerAvailability.AVAILABLE

    Scaffold(
        containerColor = if (showsCamera) MaterialTheme.colorScheme.scrim else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Scan") },
                actions = {
                    IconButton(onClick = { nav.push(Route.History) }, Modifier.testTag("scan.history")) { Icon(Icons.Rounded.History, "History") }
                    IconButton(onClick = { nav.push(Route.Make(null)) }, Modifier.testTag("scan.make")) { Icon(Icons.Rounded.QrCode2, "Make a code") }
                    IconButton(onClick = { nav.push(Route.Settings) }, Modifier.testTag("scan.settings")) { Icon(Icons.Rounded.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (showsCamera)
            Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                CameraScanner(
                    active = !sheetIsUp && resumed.isAtLeast(Lifecycle.State.RESUMED),
                    torchOn = torchOn,
                    onTorchAvailable = { torchAvailable = it },
                    onCode = { code -> scope.launch { present(code) } },
                    onMultipleChange = { multipleInView = it },
                    onStartFailure = { availability = ScannerAvailability.UNSUPPORTED },
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = Space.m).padding(bottom = Space.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.l),
                ) {
                    Spacer(Modifier.weight(1f))
                    ViewfinderFrame(onCamera = true, modifier = Modifier.sizeIn(maxWidth = 280.dp, maxHeight = 280.dp).fillMaxWidth(0.7f))
                    Hint(if (multipleInView) "Tap a code" else "Point at a code", announce = multipleInView)
                    Spacer(Modifier.weight(1f))
                    StatusLine(message, working)
                    Controls(showTorch = torchAvailable, torchOn = torchOn, onTorch = {
                        torchOn = !torchOn
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }, onPhoto = onPhoto, onPaste = onPaste)
                }
            }
        else
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Fallback(availability) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }
                StatusLine(message, working)
                Controls(showTorch = false, torchOn = false, onTorch = {}, onPhoto = onPhoto, onPaste = onPaste)
            }
    }

    candidates?.let { list ->
        PickListSheet(list, onPick = { pick ->
            nav.candidates.value = null
            scope.launch {
                model.record(pick.analysis, HistoryKind.SCANNED)
                nav.showResult(pick.analysis)
            }
        }, onCancel = { nav.candidates.value = null })
    }
}

/** One short line under the frame: what to do, or "Tap a code" when several are in view. */
@Composable
private fun Hint(text: String, announce: Boolean) {
    Surface(shape = PillShape, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
        AnimatedContent(text, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "hint") { shown ->
            Text(
                shown,
                Modifier.padding(horizontal = Space.m, vertical = Space.s).semantics { if (announce) liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatusLine(message: String?, working: Boolean) {
    when {
        message != null ->
            Surface(shape = PillShape, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
                Text(
                    message,
                    Modifier.padding(horizontal = Space.m, vertical = Space.s).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        working ->
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
                LoadingIndicatorBox()
            }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingIndicatorBox() {
    LoadingIndicator(Modifier.padding(Space.xs).semantics { stateDescription = "Reading" })
}

/** Photo and Paste flank the torch, the one control people reach for while aiming, on one platter. */
@Composable
private fun Controls(showTorch: Boolean, torchOn: Boolean, onTorch: () -> Unit, onPhoto: () -> Unit, onPaste: () -> Unit) {
    Surface(
        Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
    ) {
        Row(
            Modifier.padding(Space.ms),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = onPhoto, modifier = Modifier.weight(1f).testTag("scan.photo")) {
                Icon(Icons.Rounded.Image, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Photo", maxLines = 1)
            }
            if (showTorch)
                FilledIconToggleButton(
                    checked = torchOn,
                    onCheckedChange = { onTorch() },
                    modifier = Modifier.size(72.dp).semantics { stateDescription = if (torchOn) "On" else "Off" },
                    colors =
                        IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Icon(if (torchOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Torch", Modifier.size(32.dp))
                }
            OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f).testTag("scan.paste")) {
                Icon(Icons.Rounded.ContentPaste, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Paste", maxLines = 1)
            }
        }
    }
}

/** No camera: the same frame on paper, holding the reason, so the screen still reads as a scanner. */
@Composable
private fun Fallback(availability: ScannerAvailability, openSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Box(Modifier.padding(top = Space.l).size(220.dp), contentAlignment = Alignment.Center) {
            ViewfinderFrame(onCamera = false, modifier = Modifier.fillMaxSize())
            Icon(
                if (availability == ScannerAvailability.DENIED) Icons.Rounded.PhotoCamera else Icons.Rounded.PhotoLibrary,
                null,
                Modifier.size(48.dp),
                tint = QrColors.muted,
            )
        }
        Text(
            when (availability) {
                ScannerAvailability.DENIED -> "Camera access is off. You can still scan a photo or what you copied, or turn the camera on in Settings."
                ScannerAvailability.NOT_ASKED -> "The camera needs your permission to scan. You can still scan a photo or what you copied."
                else -> "This phone can’t scan with the camera. You can still scan a photo or what you copied."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        if (availability == ScannerAvailability.DENIED)
            Button(onClick = openSettings, modifier = Modifier.heightIn(min = 56.dp)) { Text("Open Settings") }
    }
}

/** Several codes in one photo or paste: pick one. */
@Composable
private fun PickListSheet(candidates: List<ScanCandidate>, onPick: (ScanCandidate) -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = Space.m).padding(bottom = Space.l)) {
            Text("Pick a code", Modifier.padding(start = Space.s, bottom = Space.m), style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                candidates.forEachIndexed { index, candidate ->
                    SegmentedListItem(
                        onClick = { onPick(candidate) },
                        shapes = ListItemDefaults.segmentedShapes(index, candidates.size),
                        leadingContent = { Icon(VerdictIcons.type(candidate.analysis.payload.typeId), null) },
                        supportingContent = candidate.domain?.let { { Text(it) } },
                        trailingContent = { VerdictLabel(candidate.analysis.level) },
                    ) {
                        Text(candidate.typeName)
                    }
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End).padding(top = Space.s)) { Text("Cancel") }
        }
    }
}
