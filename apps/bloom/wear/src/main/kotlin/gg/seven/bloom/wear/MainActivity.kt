package gg.seven.bloom.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
                            Text("Care")
                        }
                    }
                    if (snapshot == null)
                        item {
                            Text(
                                "Open the phone app and enable the watch companion in tracking and privacy settings."
                            )
                        }
                    else if (!snapshot.enabled)
                        item {
                            Text(
                                "Watch companion is off. Enable it on your phone to share a snapshot."
                            )
                        }
                    else {
                        snapshot.cycle_day?.let { day ->
                            item {
                                Text("Cycle day $day", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        if (!snapshot.fresh(now))
                            item { Text("This snapshot is old. Sync before recording.") }
                        if (snapshot.doses.isEmpty())
                            item { Text("No reminders in this snapshot.") }
                        snapshot.doses.forEach { dose ->
                            item { Text(dose.title, style = MaterialTheme.typography.titleMedium) }
                            item {
                                Text(
                                    "${DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(dose.at))} · ${dose.status.replaceFirstChar(Char::uppercase)}"
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
                                    label = { Text("Taken") },
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
                                    label = { Text("Skip") },
                                )
                            }
                        }
                    }
                    if (state.pending != null) {
                        item { Text("Pending. Not saved on phone yet.") }
                        item {
                            OutlinedButton(
                                onClick = { discard = true },
                                enabled = !busy,
                                modifier =
                                    Modifier.fillMaxWidth().transformedHeight(this, transformation),
                                transformation = SurfaceTransformation(transformation),
                                label = { Text("Clear pending action") },
                            )
                        }
                    }
                    message?.let { value ->
                        item { Text(value, style = MaterialTheme.typography.bodySmall) }
                    }
                    item {
                        Button(
                            onClick = { scope.launch { model.sync() } },
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth().transformedHeight(this, transformation),
                            transformation = SurfaceTransformation(transformation),
                            label = { Text(if (busy) "Syncing…" else "Sync with phone") },
                        )
                    }
                }
            }
            AlertDialog(
                visible = discard,
                onDismissRequest = { discard = false },
                title = { Text("Clear pending action?") },
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
                    Text(
                        "This only clears the watch queue. Check phone history in case the action was saved before the connection dropped."
                    )
                },
            )
        }
    }
}
