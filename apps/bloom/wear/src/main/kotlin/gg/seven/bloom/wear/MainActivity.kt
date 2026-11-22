package gg.seven.bloom.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BloomWearApp
        setContent { WatchHome(app.model) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.model.sync() }
                val stop = app.link.listen { launch { app.model.sync() } }
                try {
                    awaitCancellation()
                } finally {
                    stop()
                }
            }
        }
    }
}

@Composable
fun WatchHome(model: WatchModel) {
    val state by model.state.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val scope = rememberCoroutineScope()
    var discard by remember { mutableStateOf(false) }
    val now by
        produceState(Instant.now().epochSecond) {
            while (true) {
                value = Instant.now().epochSecond
                delay(1000)
            }
        }
    val list = rememberTransformingLazyColumnState()
    val snapshot = state.snapshot
    val canAct = snapshot?.fresh(now) == true && !busy && state.pending == null
    MaterialTheme(colorScheme = dynamicColorScheme(LocalContext.current) ?: ColorScheme()) {
        val transformation = rememberTransformationSpec()
        AppScaffold(timeText = { TimeText() }) {
            ScreenScaffold(list) { padding ->
                TransformingLazyColumn(
                    state = list,
                    contentPadding = padding,
                    modifier = Modifier.testTag("watch-list"),
                ) {
                    item {
                        ListHeader(
                            modifier =
                                Modifier.fillMaxWidth().transformedHeight(this, transformation),
                            transformation = SurfaceTransformation(transformation),
                        ) {
                            Text(stringResource(R.string.watch_title))
                        }
                    }
                    if (snapshot == null)
                        item {
                            Text(stringResource(R.string.watch_not_set_up))
                        }
                    else if (!snapshot.enabled)
                        item {
                            Text(stringResource(R.string.watch_off))
                        }
                    else {
                        snapshot.cycle_day?.let { day ->
                            item {
                                Text(
                                    stringResource(R.string.watch_cycle_day, day),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                        if (!snapshot.fresh(now))
                            item { Text(stringResource(R.string.watch_stale)) }
                        if (snapshot.doses.isEmpty())
                            item { Text(stringResource(R.string.watch_no_reminders)) }
                        snapshot.doses.forEach { dose ->
                            item { Text(dose.title, style = MaterialTheme.typography.titleMedium) }
                            item {
                                Text(
                                    stringResource(
                                        R.string.watch_dose_line,
                                        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                                            .withZone(ZoneId.systemDefault())
                                            .format(Instant.ofEpochSecond(dose.at)),
                                        statusText(dose.status),
                                    )
                                )
                            }
                            item {
                                Button(
                                    onClick = { scope.launch { model.record(dose, "taken") } },
                                    enabled = canAct,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .transformedHeight(this, transformation),
                                    transformation = SurfaceTransformation(transformation),
                                    label = { Text(stringResource(R.string.watch_taken)) },
                                )
                            }
                            item {
                                OutlinedButton(
                                    onClick = { scope.launch { model.record(dose, "skipped") } },
                                    enabled = canAct,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .transformedHeight(this, transformation),
                                    transformation = SurfaceTransformation(transformation),
                                    label = { Text(stringResource(R.string.watch_skip)) },
                                )
                            }
                        }
                    }
                    if (state.pending != null) {
                        item { Text(stringResource(R.string.watch_pending)) }
                        item {
                            OutlinedButton(
                                onClick = { discard = true },
                                enabled = !busy,
                                modifier =
                                    Modifier.fillMaxWidth().transformedHeight(this, transformation),
                                transformation = SurfaceTransformation(transformation),
                                label = { Text(stringResource(R.string.watch_clear)) },
                            )
                        }
                    }
                    message?.let { value ->
                        item { Text(messageText(value), style = MaterialTheme.typography.bodySmall) }
                    }
                    item {
                        Button(
                            onClick = { scope.launch { model.sync() } },
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth().transformedHeight(this, transformation),
                            transformation = SurfaceTransformation(transformation),
                            label = {
                                Text(
                                    stringResource(
                                        if (busy) R.string.watch_syncing else R.string.watch_sync
                                    )
                                )
                            },
                        )
                    }
                }
            }
            AlertDialog(
                visible = discard,
                onDismissRequest = { discard = false },
                title = { Text(stringResource(R.string.watch_clear_title)) },
                confirmButton = {
                    AlertDialogDefaults.ConfirmButton(
                        modifier = Modifier.testTag("clear-pending-confirm"),
                        onClick = {
                            discard = false
                            scope.launch { model.discardPending() }
                        },
                    )
                },
                dismissButton = {
                    AlertDialogDefaults.DismissButton(
                        modifier = Modifier.testTag("clear-pending-cancel"),
                        onClick = { discard = false },
                    )
                },
                text = {
                    Text(stringResource(R.string.watch_clear_body))
                },
            )
        }
    }
}

@Composable
private fun messageText(message: WatchMessage): String =
    when (message) {
        is WatchMessage.Phone -> message.text
        WatchMessage.Local.RefreshFirst -> stringResource(R.string.watch_refresh_first)
        WatchMessage.Local.Pending -> stringResource(R.string.watch_pending)
        WatchMessage.Local.CouldNotKeep -> stringResource(R.string.watch_could_not_keep)
        WatchMessage.Local.UpToDate -> stringResource(R.string.watch_up_to_date)
        WatchMessage.Local.Disconnected -> stringResource(R.string.watch_disconnected)
        WatchMessage.Local.NotConfirmed -> stringResource(R.string.watch_not_confirmed)
        WatchMessage.Local.Cleared -> stringResource(R.string.watch_cleared)
        WatchMessage.Local.CouldNotClear -> stringResource(R.string.watch_could_not_clear)
    }

/** A dose status code from the phone's snapshot, in words; unknown codes are shown as sent. */
@Composable
private fun statusText(status: String): String =
    when (status) {
        "pending" -> stringResource(R.string.status_pending)
        "taken" -> stringResource(R.string.status_taken)
        "skipped" -> stringResource(R.string.status_skipped)
        "snoozed" -> stringResource(R.string.status_snoozed)
        else -> status
    }
