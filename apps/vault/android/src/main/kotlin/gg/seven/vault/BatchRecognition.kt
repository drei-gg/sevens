@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.vault

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
        "Queue",
        model,
        model::cleanPresentation,
        onSave = if (queue.ready && !queue.stopped) ({ model.saveBatchItem(queue) }) else null,
        saveEnabled =
            queue.name.isNotBlank() && queue.text.isNotBlank() && model.store.paidFeaturesAvailable,
        saveLabel = "Save note",
        dirty = queue.ready && queue.text.isNotBlank(),
    ) {
        Text(
            "${queue.saved} saved · ${queue.skipped} skipped",
            Modifier.testTag("ocr-batch-counts"),
        )
        LinearWavyProgressIndicator(
            progress = { queue.index.toFloat() / queue.plan.items.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!queue.started) {
            Text(
                "Review ${queue.plan.items.size} files",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Recognize one file at a time, then save an encrypted note or skip it. All pages of each PDF are included, up to ${queue.plan.maximumPdfPages}; longer PDFs need the individual page-range action. Originals stay unchanged."
            )
            queue.plan.items.forEach { Text(it.name) }
            Text(
                "The queue stays only in this unlocked session. Closing or locking discards unsaved text; notes already saved remain."
            )
            Button(
                onClick = { queue.started = true },
                modifier = Modifier.testTag("ocr-batch-start"),
            ) {
                Text("Start review")
            }
        } else if (queue.stopped || item == null) {
            Text(
                if (queue.stopped)
                    (if (model.busy) "Stopping after current page…" else "Queue stopped")
                else "Review complete",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Saved notes remain in their source folders. Skipped or unfinished files were not changed."
            )
        } else {
            Text(
                "File ${queue.index + 1} of ${queue.plan.items.size}: ${item.name}",
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
                    Text("Retry this file")
                }
            }
            if (queue.ready) {
                if (queue.text.isBlank())
                    Text("No text was recognized. Enter text or skip this file.")
                OutlinedTextField(
                    queue.name,
                    { queue.name = it },
                    label = { Text("Note name") },
                    enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().testTag("ocr-batch-name"),
                )
                OutlinedTextField(
                    queue.text,
                    { queue.text = it },
                    label = { Text("Recognized text") },
                    enabled = !model.busy,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("ocr-batch-text"),
                )
                if (!model.store.paidFeaturesAvailable)
                    Text(
                        "Start the trial or unlock to save new notes. You can still read and copy this text."
                    )
            }
            if (!model.busy)
                TextButton(
                    onClick = { queue.next(false) },
                    modifier = Modifier.testTag("ocr-batch-skip"),
                ) {
                    Text("Skip this file")
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
                Text(if (model.busy) "Stop after current page" else "Stop queue")
            }
        }
    }
    if (stopping)
        AlertDialog(
            onDismissRequest = { stopping = false },
            title = { Text("Stop this queue?") },
            text = { Text("Unsaved text will be discarded. Notes already saved remain.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        queue.stopped = true
                        queue.text = ""
                        stopping = false
                    }
                ) {
                    Text("Stop and discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { stopping = false }) { Text("Keep reviewing") }
            },
        )
}
