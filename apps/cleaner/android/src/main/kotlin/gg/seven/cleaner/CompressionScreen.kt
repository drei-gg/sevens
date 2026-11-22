@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package gg.seven.cleaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.ImageSizeProfile
import gg.seven.cleaner.core.assessmentCanOfferCopy
import gg.seven.cleaner.core.assessmentSavings
import gg.seven.kit.Space
import gg.seven.kit.numeric
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The compression review (iOS CompressionScreen, a sheet there): a full-screen dialog, because the
 * person inspects two large images side by side. Discard closes it; saving keeps the original.
 */
@Composable
fun CompressionScreen(model: CleanerModel, candidate: CompressionCandidate) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val queue by model.compressionBatch.collectAsStateWithLifecycle()
    val receipt by model.createdCopyID.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val canCancel by model.canCancel.collectAsStateWithLifecycle()
    val close = { if (queue != null) model.stopCompressionBatch() else model.discardCandidate() }
    var showFailure by rememberSaveable(error) { mutableStateOf(error != null) }
    val assessment = candidate.assessment
    val savings = assessmentSavings(assessment)
    var detail by rememberSaveable { mutableStateOf(false) }
    var quality by
        rememberSaveable(candidate.output.path) {
            mutableFloatStateOf((candidate.quality * 100).toFloat())
        }
    var size by rememberSaveable(candidate.output.path) { mutableStateOf(candidate.size) }
    val needsPreview =
        size != candidate.size ||
            kotlin.math.round(quality).toInt() != kotlin.math.round(candidate.quality * 100).toInt()
    Dialog(
        onDismissRequest = { if (!busy) close() },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Review", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(
                            onClick = close,
                            enabled = !busy,
                            modifier = Modifier.testTag("compression-discard"),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                if (queue != null) "Stop review" else "Close review",
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CompressionActions {
                        PrimaryButton(
                            if (receipt != null) "Retry verification" else "Save copy",
                            Modifier.testTag("compression-save"),
                            enabled =
                                !busy &&
                                    (receipt != null ||
                                        (assessmentCanOfferCopy(assessment) && !needsPreview)),
                        ) {
                            model.saveCandidate()
                        }
                        if (queue != null)
                            TextButton(
                                onClick = model::skipBatchPhoto,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("compression-skip"),
                            ) {
                                Text(
                                    if (receipt != null) "Leave copy unverified and continue"
                                    else "Skip this photo"
                                )
                            }
                        Text(
                            "Your original stays. The copy needs extra storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                Readable(Modifier.padding(bottom = Space.l)) {
                    queue?.let { CompressionBatchProgress(it) }
                    error?.let { CleanerNotice(it, Icons.Outlined.Warning, caution = true) }
                    if (receipt != null)
                        Text(
                            "A copy exists. Retry checks and records that same copy. Closing keeps it, but leaves it unverified.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    CleanerHero(
                        "Inspect the actual files",
                        if (savings > 0) byteText(savings) else "No reduction",
                        "Candidate reduction. The original stays.",
                        Icons.Outlined.Compress,
                        hue = Hues.warm,
                        big = savings > 0,
                    )
                    Text(
                        "Checks cannot judge every detail. Zoom in before saving.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Quality: ${quality.toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = quality,
                        onValueChange = { quality = kotlin.math.round(it) },
                        valueRange = 90f..100f,
                        steps = 9,
                        enabled = !busy && receipt == null,
                        modifier =
                            Modifier.testTag("compression-quality").semantics {
                                contentDescription = "Quality"
                            },
                    )
                    Text(
                        "Update the preview after changing quality or size.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    gg.seven.kit.DropdownField(
                        "Image size",
                        size,
                        listOf(
                            ImageSizeProfile.ORIGINAL to "Original size",
                            ImageSizeProfile.LARGE to "2400 px max",
                            ImageSizeProfile.MEDIUM to "1600 px max",
                            ImageSizeProfile.SMALL to "800 px max",
                        ),
                        modifier = Modifier.testTag("compression-size"),
                        enabled = !busy && receipt == null,
                        onSelect = { size = it },
                    )
                    if (size != ImageSizeProfile.ORIGINAL)
                        Text("Resizing removes fine detail and never enlarges your image.")
                    OutlinedButton(
                        onClick = { model.updateCandidateQuality(quality.toDouble() / 100, size) },
                        enabled = needsPreview && !busy && receipt == null,
                        modifier = Modifier.fillMaxWidth().testTag("compression-update"),
                    ) {
                        Text("Update preview")
                    }
                    if (busy) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        Text(progress, style = MaterialTheme.typography.bodyMedium)
                        if (canCancel)
                            TextButton(onClick = model::cancel) { Text("Cancel preparation") }
                    }
                    Text(
                        "Original: ${assessment.source.width} × ${assessment.source.height}. Output: ${assessment.output.width} × ${assessment.output.height}. Quality setting: ${"%.2f".format(candidate.quality)}",
                        style = MaterialTheme.typography.bodyMedium.numeric(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val pair: @Composable (Modifier) -> Unit = { m ->
                        Comparison("Original", candidate.source, assessment.source.bytes, m)
                    }
                    val next: @Composable (Modifier) -> Unit = { m ->
                        Comparison("Candidate", candidate.output, assessment.output.bytes, m)
                    }
                    BoxWithConstraints {
                        if (maxWidth >= 560.dp && !largeText())
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                pair(Modifier.weight(1f))
                                next(Modifier.weight(1f))
                            }
                        else
                            Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                                pair(Modifier)
                                next(Modifier)
                            }
                    }

                    if (assessment.issues.isEmpty())
                        Text(
                            "The current profile checks passed. Original retained when saving.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    else
                        assessment.issues.forEach {
                            CleanerNotice(it, Icons.Outlined.Warning, caution = true)
                        }
                    if (candidate.size != ImageSizeProfile.ORIGINAL)
                        Text(
                            "Detail scores compare the encoded copy with a resized reference. They do not measure the detail discarded by resizing.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    Column {
                        TextButton(onClick = { detail = !detail }) {
                            Text("Detail comparison")
                            Icon(
                                if (detail) Icons.Outlined.ExpandLess
                                else Icons.Outlined.ExpandMore,
                                null,
                            )
                        }
                        if (detail)
                            Text(
                                "Full-resolution PSNR: ${"%.1f".format(assessment.fullResolutionPsnr)} dB\nWorst detail tile: ${"%.1f".format(assessment.worstTilePsnr)} dB\nThese are diagnostic thresholds, not a perceptual quality certificate.",
                                style = MaterialTheme.typography.bodySmall.numeric(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            }
        }
    }
    if (showFailure && error != null)
        AlertDialog(
            onDismissRequest = { showFailure = false },
            title = {
                Text(if (receipt != null) "Could not finish saving" else "Could not complete")
            },
            text = { Text(error.orEmpty(), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showFailure = false }) { Text("OK") } },
        )
}

@Composable
private fun Comparison(title: String, file: File, bytes: Long, modifier: Modifier) {
    val bitmap by
        produceState<Bitmap?>(null, file) {
            value =
                withContext(Dispatchers.IO) { runCatching { decodeForScreen(file) }.getOrNull() }
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        bitmap?.let { ZoomImage(it, "$title image", Modifier.fillMaxWidth().height(280.dp)) }
        Text(byteText(bytes), style = MaterialTheme.typography.titleLarge.numeric())
    }
}

/** Large enough to inspect detail when zoomed, without holding two 16 MP bitmaps for display. */
private fun decodeForScreen(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 3072) sample *= 2
    return BitmapFactory.decodeFile(
        file.path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
