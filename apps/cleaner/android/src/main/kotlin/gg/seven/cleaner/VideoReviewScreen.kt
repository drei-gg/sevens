@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package gg.seven.cleaner

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.*
import gg.seven.kit.*
import java.io.File
import java.util.Locale

class VideoReview(val item: CleanupItem, val source: File, val inspection: VideoInspection) {
    var start by mutableStateOf("0.000")
    var end by mutableStateOf(seconds(inspection.facts.durationMs))
    var candidate by mutableStateOf<VideoCandidate?>(null)
    var createdID by mutableStateOf<String?>(null)
    var savedID by mutableStateOf<String?>(null)

    fun plan(): VideoCopyPlan? =
        runCatching {
                val a = start.replace(',', '.').toDouble()
                val b = end.replace(',', '.').toDouble()
                require(
                    a.isFinite() &&
                        b.isFinite() &&
                        a >= 0 &&
                        b <= inspection.facts.durationMs.toDouble() / 1000
                )
                videoCopyPlan(
                    inspection.facts,
                    kotlin.math.round(a * 1000).toULong(),
                    kotlin.math.round(b * 1000).toULong(),
                )
            }
            .getOrNull()

    companion object {
        fun seconds(ms: ULong) = String.format(Locale.ROOT, "%.3f", ms.toDouble() / 1000)
    }
}

@Composable
fun VideoReviewScreen(model: CleanerModel, review: VideoReview) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val canCancel by model.canCancel.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val plan = review.plan()
    val candidate = review.candidate
    val finished = review.savedID != null
    val locked = busy || review.createdID != null
    val slider =
        rememberRangeSliderState(
            endValue = review.inspection.facts.durationMs.toFloat() / 1000,
            trackRange = 0f..review.inspection.facts.durationMs.toFloat() / 1000,
        )
    SideEffect {
        plan?.let {
            slider.startValue = it.startMs.toFloat() / 1000
            slider.endValue = it.endMs.toFloat() / 1000
        }
    }
    FullScreenForm(
        title =
            stringResource(if (finished) R.string.video_saved_title else R.string.video_review_title),
        onClose = model::closeVideo,
        onSave = if (finished) model::closeVideo else model::saveVideo,
        busy = busy,
        saveLabel =
            stringResource(
                if (finished) R.string.action_done
                else if (review.createdID != null) R.string.action_retry
                else R.string.compress_save_copy
            ),
        saveEnabled =
            finished || (candidate != null && plan == candidate.plan && candidate.issues.isEmpty()),
        confirmDiscard = !finished && candidate != null && review.createdID == null,
    ) {
        Text(
            stringResource(if (finished) R.string.video_saved_note else R.string.video_review_note)
        )
        Text(
            stringResource(R.string.video_profile_note),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!finished) {
            RangeSlider(
                state = slider,
                onValueChange = {
                    slider.startValue = it.start
                    slider.endValue = it.endInclusive
                    review.start = VideoReview.seconds((it.start * 1000).toULong())
                    review.end = VideoReview.seconds((it.endInclusive * 1000).toULong())
                },
                enabled = !locked,
                modifier = Modifier.testTag("video.range"),
            )
            OutlinedTextField(
                review.start,
                { review.start = it },
                label = { Text(stringResource(R.string.video_start)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !locked,
                isError = plan == null,
                modifier = Modifier.fillMaxWidth().testTag("video.start"),
            )
            OutlinedTextField(
                review.end,
                { review.end = it },
                label = { Text(stringResource(R.string.video_end)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !locked,
                isError = plan == null,
                supportingText = {
                    Text(
                        stringResource(
                            R.string.video_range_hint,
                            VideoReview.seconds(review.inspection.facts.durationMs),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("video.end"),
            )
            Button(
                onClick = model::prepareVideo,
                enabled = !locked && plan != null && plan != candidate?.plan,
                modifier = Modifier.testTag("video.prepare"),
            ) {
                Text(
                    stringResource(
                        if (candidate == null) R.string.batch_prepare
                        else R.string.compress_update_preview
                    )
                )
            }
        }
        if (busy) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            Text(progress)
            if (canCancel) TextButton(onClick = model::cancel) {
                Text(stringResource(R.string.compress_cancel_preparation))
            }
        }
        if (review.createdID != null && !finished)
            Text(stringResource(R.string.video_copy_exists))
        if (candidate != null) {
            Text(
                stringResource(
                    R.string.video_sizes,
                    byteText(candidate.source.length()),
                    byteText(candidate.output.length()),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            if (candidate.output.length() >= candidate.source.length())
                Text(stringResource(R.string.video_not_smaller))
            Text(
                stringResource(
                    R.string.video_result,
                    candidate.after.facts.width.toInt(),
                    candidate.after.facts.height.toInt(),
                    VideoReview.seconds(candidate.after.facts.durationMs),
                ),
                modifier = Modifier.testTag("video.result"),
            )
            key(candidate.output.path) {
                VideoPreview(
                    review.item.copy(
                        id = candidate.output.toURI().toString(),
                        duration = candidate.after.facts.durationMs.toDouble() / 1000,
                    )
                )
            }
            candidate.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Text(
            stringResource(R.string.video_metadata_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { model.error.value = null },
            title = { Text(stringResource(R.string.video_not_saved)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { model.error.value = null }) {
                    Text(stringResource(R.string.video_keep_editing))
                }
            },
        )
    }
}
