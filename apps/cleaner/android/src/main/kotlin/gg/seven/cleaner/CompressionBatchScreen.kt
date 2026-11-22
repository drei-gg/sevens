@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package gg.seven.cleaner

import androidx.compose.ui.res.stringResource
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
        if (queue.complete) stringResource(R.string.batch_complete)
        else stringResource(R.string.batch_position, queue.index + 1, queue.items.size),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag("compression-batch-position"),
    )
    LinearWavyProgressIndicator(
        progress = { queue.index.toFloat() / queue.items.size },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(
            R.string.batch_summary,
            queue.saved.size,
            queue.skipped.size,
            queue.unverified.size,
        ),
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
                    title = { Text(stringResource(R.string.batch_title)) },
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
                                stringResource(
                                    if (queue.complete) R.string.compress_close_review
                                    else R.string.compress_stop_review
                                ),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                CompressionActions {
                    if (queue.complete)
                        PrimaryButton(stringResource(R.string.action_done), Modifier.testTag("compression-batch-done")) {
                            model.stopCompressionBatch()
                        }
                    else {
                        PrimaryButton(
                            stringResource(
                                if (error != null) R.string.batch_retry_preparation
                                else R.string.batch_prepare
                            ),
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
                                Text(stringResource(R.string.compress_cancel_preparation))
                            }
                        else
                            TextButton(
                                onClick = model::skipBatchPhoto,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("compression-skip"),
                            ) {
                                Text(stringResource(R.string.compress_skip_photo))
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
                        stringResource(
                            if (queue.complete) R.string.batch_done_note else R.string.batch_note
                        )
                    )
                    if (!queue.complete)
                        Text(
                            stringResource(R.string.batch_stop_note),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    if (queue.unverified.isNotEmpty())
                        CleanerNotice(
                            stringResource(R.string.batch_unverified_note),
                            Icons.Outlined.Warning,
                            caution = true,
                        )
                    if (queue.index > 0) {
                        HorizontalDivider()
                        Text(stringResource(R.string.batch_results), style = MaterialTheme.typography.titleMedium)
                        queue.items.take(queue.index).forEach { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            when (item.id) {
                                                in queue.saved -> R.string.batch_result_saved
                                                in queue.unverified -> R.string.batch_result_unverified
                                                else -> R.string.batch_result_skipped
                                            }
                                        )
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
