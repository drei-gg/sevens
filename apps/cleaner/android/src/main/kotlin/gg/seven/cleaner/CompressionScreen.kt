@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package gg.seven.cleaner

import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
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
                    title = { Text(stringResource(R.string.compress_review), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(
                            onClick = close,
                            enabled = !busy,
                            modifier = Modifier.testTag("compression-discard"),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                stringResource(
                                    if (queue != null) R.string.compress_stop_review
                                    else R.string.compress_close_review
                                ),
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
                            stringResource(
                                if (receipt != null) R.string.compress_retry_verification
                                else R.string.compress_save_copy
                            ),
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
                                    stringResource(
                                        if (receipt != null) R.string.compress_leave_unverified
                                        else R.string.compress_skip_photo
                                    )
                                )
                            }
                        Text(
                            stringResource(R.string.compress_original_stays),
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
                            stringResource(R.string.compress_copy_exists),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    CleanerHero(
                        stringResource(R.string.compress_inspect),
                        if (savings > 0) byteText(savings)
                        else stringResource(R.string.compress_no_reduction),
                        stringResource(R.string.compress_reduction_detail),
                        Icons.Outlined.Compress,
                        hue = Hues.warm,
                        big = savings > 0,
                    )
                    Text(
                        stringResource(R.string.compress_zoom_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.compress_quality_value, quality.toInt()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val qualityLabel = stringResource(R.string.compress_quality)
                    Slider(
                        value = quality,
                        onValueChange = { quality = kotlin.math.round(it) },
                        valueRange = 90f..100f,
                        steps = 9,
                        enabled = !busy && receipt == null,
                        modifier =
                            Modifier.testTag("compression-quality").semantics {
                                contentDescription = qualityLabel
                            },
                    )
                    Text(
                        stringResource(R.string.compress_update_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    gg.seven.kit.DropdownField(
                        stringResource(R.string.compress_image_size),
                        size,
                        listOf(
                            ImageSizeProfile.ORIGINAL to stringResource(R.string.compress_size_original),
                            ImageSizeProfile.LARGE to stringResource(R.string.compress_size_2400),
                            ImageSizeProfile.MEDIUM to stringResource(R.string.compress_size_1600),
                            ImageSizeProfile.SMALL to stringResource(R.string.compress_size_800),
                        ),
                        modifier = Modifier.testTag("compression-size"),
                        enabled = !busy && receipt == null,
                        onSelect = { size = it },
                    )
                    if (size != ImageSizeProfile.ORIGINAL)
                        Text(stringResource(R.string.compress_resize_note))
                    OutlinedButton(
                        onClick = { model.updateCandidateQuality(quality.toDouble() / 100, size) },
                        enabled = needsPreview && !busy && receipt == null,
                        modifier = Modifier.fillMaxWidth().testTag("compression-update"),
                    ) {
                        Text(stringResource(R.string.compress_update_preview))
                    }
                    if (busy) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        Text(progress, style = MaterialTheme.typography.bodyMedium)
                        if (canCancel)
                            TextButton(onClick = model::cancel) {
                                Text(stringResource(R.string.compress_cancel_preparation))
                            }
                    }
                    Text(
                        stringResource(
                            R.string.compress_dimensions,
                            assessment.source.width,
                            assessment.source.height,
                            assessment.output.width,
                            assessment.output.height,
                            "%.2f".format(candidate.quality),
                        ),
                        style = MaterialTheme.typography.bodyMedium.numeric(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val pair: @Composable (Modifier) -> Unit = { m ->
                        Comparison(stringResource(R.string.compress_original), candidate.source, assessment.source.bytes, m)
                    }
                    val next: @Composable (Modifier) -> Unit = { m ->
                        Comparison(stringResource(R.string.compress_candidate), candidate.output, assessment.output.bytes, m)
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
                            stringResource(R.string.compress_checks_passed),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    else
                        assessment.issues.forEach {
                            CleanerNotice(it, Icons.Outlined.Warning, caution = true)
                        }
                    if (candidate.size != ImageSizeProfile.ORIGINAL)
                        Text(
                            stringResource(R.string.compress_resized_scores),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    Column {
                        TextButton(onClick = { detail = !detail }) {
                            Text(stringResource(R.string.compress_detail_comparison))
                            Icon(
                                if (detail) Icons.Outlined.ExpandLess
                                else Icons.Outlined.ExpandMore,
                                null,
                            )
                        }
                        if (detail)
                            Text(
                                stringResource(
                                    R.string.compress_psnr,
                                    "%.1f".format(assessment.fullResolutionPsnr),
                                    "%.1f".format(assessment.worstTilePsnr),
                                ),
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
                Text(
                    stringResource(
                        if (receipt != null) R.string.compress_save_failed else R.string.error_title
                    )
                )
            },
            text = { Text(error.orEmpty(), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showFailure = false }) { Text(stringResource(R.string.action_ok)) } },
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
        bitmap?.let { ZoomImage(it, stringResource(R.string.compress_image_description, title), Modifier.fillMaxWidth().height(280.dp)) }
        Text(byteText(bytes), style = MaterialTheme.typography.titleLarge.numeric())
    }
}

/** Large enough to inspect detail when zoomed, without holding two 16 MP bitmaps for display. */
/** Upright by EXIF orientation; the candidate keeps the original's orientation tag. */
private fun decodeForScreen(file: File): Bitmap? = UprightImages.decode(file, 3072)
