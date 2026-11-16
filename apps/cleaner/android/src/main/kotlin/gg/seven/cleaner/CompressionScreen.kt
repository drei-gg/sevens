@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package gg.seven.cleaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val assessment = candidate.assessment
    val savings = assessmentSavings(assessment)
    var detail by rememberSaveable { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (!busy) model.discardCandidate() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Compression review", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = model::discardCandidate, enabled = !busy, modifier = Modifier.testTag("compression-discard")) {
                            Icon(Icons.Outlined.Close, "Discard")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.navigationBarsPadding().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        PrimaryButton("Save copy and keep original", Modifier.testTag("compression-save"), enabled = assessmentCanOfferCopy(assessment) && !busy) {
                            model.saveCandidate()
                        }
                        Text(
                            "Saving a copy initially uses more storage. This build does not replace or automatically delete originals.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                Readable(Modifier.padding(bottom = Space.l)) {
                    CleanerHero(
                        "Inspect the actual files",
                        if (savings > 0) byteText(savings) else "No reduction",
                        "Candidate reduction. The original stays.",
                        Icons.Outlined.Compress,
                        hue = Hues.warm,
                        big = savings > 0,
                    )
                    Text(
                        "Pinch to inspect fine detail. These checks reject obvious damage; they cannot guarantee that a photograph still looks right to you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val pair: @Composable (Modifier) -> Unit = { m ->
                        Comparison("Original", candidate.source, assessment.source.bytes, m)
                    }
                    val next: @Composable (Modifier) -> Unit = { m -> Comparison("Candidate", candidate.output, assessment.output.bytes, m) }
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
                    Text(
                        "Dimensions: ${assessment.output.width} × ${assessment.output.height}. Quality setting: ${"%.2f".format(candidate.quality)}",
                        style = MaterialTheme.typography.bodyMedium.numeric(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (assessment.issues.isEmpty()) Text("The current profile checks passed. Original retained when saving.", style = MaterialTheme.typography.bodyLarge)
                    else assessment.issues.forEach { CleanerNotice(it, Icons.Outlined.Warning, caution = true) }
                    Column {
                        TextButton(onClick = { detail = !detail }) {
                            Text("Detail comparison")
                            Icon(if (detail) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
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
}

@Composable
private fun Comparison(title: String, file: File, bytes: Long, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { decodeForScreen(file) }.getOrNull() }
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
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
