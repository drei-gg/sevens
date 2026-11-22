@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.vault

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import gg.seven.vault.core.*

/** Only the current review holds plaintext. This object is never saved or serialized. */
class RecognitionQueue(val plan: RecognitionBatchPlan) : Presentation {
    var started by mutableStateOf(false)
    var index by mutableIntStateOf(0)
    var saved by mutableIntStateOf(0)
    var skipped by mutableIntStateOf(0)
    var ready by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var text by mutableStateOf("")
    var name by mutableStateOf("")
    var stopped by mutableStateOf(false)
    val item
        get() = plan.items.getOrNull(index)

    fun next(savedNote: Boolean) {
        if (savedNote) saved++ else skipped++
        text = ""
        name = ""
        failure = null
        ready = false
        index++
    }
}

@Composable
fun BatchRecognitionScreen(model: VaultModel, queue: RecognitionQueue) {
    var stopping by remember { mutableStateOf(false) }
    val item = queue.item
    LaunchedEffect(queue.started, queue.index, model.busy, queue.stopped) {
        if (
            queue.started &&
                !queue.stopped &&
                !queue.ready &&
                queue.failure == null &&
                item != null &&
                !model.busy
        )
            model.recognizeBatchItem(queue)
    }
    VaultForm(
        stringResource(R.string.queue_title),
        model,
        model::cleanPresentation,
        onSave = if (queue.ready && !queue.stopped) ({ model.saveBatchItem(queue) }) else null,
        saveEnabled =
            queue.name.isNotBlank() && queue.text.isNotBlank() && model.store.paidFeaturesAvailable,
        saveLabel = stringResource(R.string.review_save),
        dirty = queue.ready && queue.text.isNotBlank(),
    ) {
        Text(
            stringResource(
                R.string.queue_counts,
                pluralStringResource(R.plurals.queue_saved, queue.saved, queue.saved),
                pluralStringResource(R.plurals.queue_skipped, queue.skipped, queue.skipped),
            ),
            Modifier.testTag("ocr-batch-counts"),
        )
        LinearWavyProgressIndicator(
            progress = { queue.index.toFloat() / queue.plan.items.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!queue.started) {
            Text(
                pluralStringResource(R.plurals.queue_review_files, queue.plan.items.size, queue.plan.items.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                pluralStringResource(
                    R.plurals.queue_intro,
                    queue.plan.maximumPdfPages.toInt(),
                    queue.plan.maximumPdfPages.toInt(),
                )
            )
            queue.plan.items.forEach { Text(it.name) }
            Text(stringResource(R.string.queue_session))
            Button(
                onClick = { queue.started = true },
                modifier = Modifier.testTag("ocr-batch-start"),
            ) {
                Text(stringResource(R.string.queue_start))
            }
        } else if (queue.stopped || item == null) {
            Text(
                stringResource(
                    if (queue.stopped)
                        (if (model.busy) R.string.queue_stopping else R.string.queue_stopped)
                    else R.string.queue_complete
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.queue_after))
        } else {
            Text(
                stringResource(R.string.queue_file, queue.index + 1, queue.plan.items.size, item.name),
                style = MaterialTheme.typography.titleMedium,
            )
            queue.failure?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ocr-batch-error"),
                )
                OutlinedButton(
                    onClick = { model.recognizeBatchItem(queue) },
                    enabled = !model.busy,
                ) {
                    Text(stringResource(R.string.queue_retry))
                }
            }
            if (queue.ready) {
                if (queue.text.isBlank())
                    Text(stringResource(R.string.queue_nothing))
                OutlinedTextField(
                    queue.name,
                    { queue.name = it },
                    label = { Text(stringResource(R.string.note_name)) },
                    enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().testTag("ocr-batch-name"),
                )
                OutlinedTextField(
                    queue.text,
                    { queue.text = it },
                    label = { Text(stringResource(R.string.review_text)) },
                    enabled = !model.busy,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("ocr-batch-text"),
                )
                if (!model.store.paidFeaturesAvailable)
                    Text(stringResource(R.string.review_gated))
            }
            if (!model.busy)
                TextButton(
                    onClick = { queue.next(false) },
                    modifier = Modifier.testTag("ocr-batch-skip"),
                ) {
                    Text(stringResource(R.string.queue_skip))
                }
            TextButton(
                onClick = {
                    if (queue.ready && queue.text.isNotBlank()) stopping = true
                    else {
                        queue.stopped = true
                        queue.text = ""
                    }
                },
                modifier = Modifier.testTag("ocr-batch-stop"),
                enabled = !model.busy || !queue.ready,
            ) {
                Text(stringResource(if (model.busy) R.string.queue_stop_after_page else R.string.queue_stop))
            }
        }
    }
    if (stopping)
        AlertDialog(
            onDismissRequest = { stopping = false },
            title = { Text(stringResource(R.string.queue_stop_title)) },
            text = { Text(stringResource(R.string.queue_stop_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        queue.stopped = true
                        queue.text = ""
                        stopping = false
                    }
                ) {
                    Text(stringResource(R.string.queue_stop_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { stopping = false }) { Text(stringResource(R.string.queue_keep_reviewing)) }
            },
        )
}
