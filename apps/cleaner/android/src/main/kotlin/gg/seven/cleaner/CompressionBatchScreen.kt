@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package gg.seven.cleaner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.Space

@Composable
internal fun CompressionBatchProgress(queue: CompressionBatch) {
    Text(
        if (queue.complete) "Review complete"
        else "Photo ${queue.index + 1} of ${queue.items.size}",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag("compression-batch-position"),
    )
    LinearWavyProgressIndicator(
        progress = { queue.index.toFloat() / queue.items.size },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "${queue.saved.size} saved · ${queue.skipped.size} skipped · ${queue.unverified.size} unverified",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("compression-batch-summary"),
    )
    queue.current?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
}

/** One original/candidate pair at a time; each save is an explicit decision. */
@Composable
fun CompressionBatchScreen(model: CleanerModel, queue: CompressionBatch) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val canCancel by model.canCancel.collectAsStateWithLifecycle()
    Dialog(
        onDismissRequest = { if (!busy) model.stopCompressionBatch() },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Photo review") },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                    navigationIcon = {
                        IconButton(
                            onClick = model::stopCompressionBatch,
                            enabled = !busy,
                            modifier = Modifier.testTag("compression-batch-close"),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                if (queue.complete) "Close review" else "Stop review",
                            )
                        }
                    },
                )
            },
            bottomBar = {
                CompressionActions {
                    if (queue.complete)
                        PrimaryButton("Done", Modifier.testTag("compression-batch-done")) {
                            model.stopCompressionBatch()
                        }
                    else {
                        PrimaryButton(
                            if (error != null) "Retry preparation" else "Prepare preview",
                            Modifier.testTag("compression-batch-prepare"),
                            enabled = !busy,
                        ) {
                            model.prepareBatchPhoto()
                        }
                        if (busy && canCancel)
                            TextButton(
                                onClick = model::cancel,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Cancel preparation")
                            }
                        else
                            TextButton(
                                onClick = model::skipBatchPhoto,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("compression-skip"),
                            ) {
                                Text("Skip this photo")
                            }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                Readable(Modifier.padding(vertical = Space.m)) {
                    CompressionBatchProgress(queue)
                    error?.let { CleanerNotice(it, Icons.Outlined.Warning, caution = true) }
                    if (busy) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        Text(progress)
                    }
                    Text(
                        if (queue.complete)
                            "Your originals and review decisions are unchanged. Saved copies remain in Photos."
                        else
                            "Inspect each photo, then save a copy or skip. Only one preview is prepared at a time. Originals stay in Photos."
                    )
                    if (!queue.complete)
                        Text(
                            "Stopping keeps saved copies and clears the current preview. This queue does not resume after the app closes.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    if (queue.unverified.isNotEmpty())
                        CleanerNotice(
                            "Unverified copies may exist in Photos. Check them before starting another queue; they were not counted as saved.",
                            Icons.Outlined.Warning,
                            caution = true,
                        )
                    if (queue.index > 0) {
                        HorizontalDivider()
                        Text("Results", style = MaterialTheme.typography.titleMedium)
                        queue.items.take(queue.index).forEach { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = {
                                    Text(
                                        when (item.id) {
                                            in queue.saved -> "Copy saved and verified"
                                            in queue.unverified ->
                                                "Copy created; verification unfinished"
                                            else -> "Skipped; original retained"
                                        }
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompressionActions(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 672.dp).fillMaxWidth().padding(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.s),
                content = content,
            )
        }
    }
}
