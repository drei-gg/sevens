package gg.seven.fast.wear

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import gg.seven.fast.wire.WireAction
import gg.seven.fast.wire.WireRequest
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Apple Watch home screen (apps/fast/Watch), item for item, in Wear Material 3. */
@Composable
fun WatchHome(model: WatchModel) {
    val snapshot by model.snapshot.collectAsState()
    val pending by model.pending.collectAsState()
    val busy by model.busy.collectAsState()
    val status by model.status.collectAsState()
    val reachable by model.reachable.collectAsState()
    var endRequest by remember { mutableStateOf<WireRequest?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canAct = reachable && !busy && pending == null
    val now by
        produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1000 - value % 1000)
            }
        }
    val listState = rememberTransformingLazyColumnState()
    MaterialTheme(colorScheme = SevensWatchColors) {
        AppScaffold(timeText = { TimeText() }) {
            ScreenScaffold(listState) { padding ->
                Box(Modifier.fillMaxSize()) {
                    snapshot?.goalProgress(now)?.let { progress ->
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxSize(),
                            allowProgressOverflow = true,
                            colors = ProgressIndicatorDefaults.colors(indicatorColor = TimeHue),
                        )
                    }
                    TransformingLazyColumn(state = listState, contentPadding = padding) {
                        item { ListHeader { Text(stringResource(R.string.title)) } }
                        val current = snapshot
                        if (current != null) {
                            val start = current.start
                            if (start != null) {
                                item { Text(stringResource(R.string.fasting), style = MaterialTheme.typography.titleMedium) }
                                item { Text(elapsedText(now - start), style = MaterialTheme.typography.displaySmall) }
                                current.goal?.let { goal ->
                                    item {
                                        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
                                        Text(
                                            stringResource(R.string.goal, DateFormat.format(pattern, Date(goal)).toString()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                item {
                                    Button(
                                        onClick = { endRequest = WireRequest.create(WireAction.END, current.revision, current.activeId) },
                                        enabled = canAct,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                        label = { Text(stringResource(R.string.end_fast)) },
                                    )
                                }
                            } else {
                                item { Text(stringResource(R.string.own_pace), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
                                item {
                                    Button(
                                        onClick = { scope.launch { model.perform(WireAction.START) } },
                                        enabled = canAct && current.canCreateRecords,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.start_fast)) },
                                    )
                                }
                            }
                            item { Text(stringResource(R.string.water, current.waterMilliliters.toInt()), style = MaterialTheme.typography.bodySmall) }
                            item {
                                FilledTonalButton(
                                    onClick = { scope.launch { model.perform(WireAction.WATER) } },
                                    enabled = canAct && current.canCreateRecords,
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = { Icon(painterResource(R.drawable.ic_water), contentDescription = null, tint = WaterHue) },
                                    label = { Text(stringResource(R.string.add_water)) },
                                )
                            }
                            item {
                                val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "MMMdHm" else "MMMdhm")
                                Text(
                                    stringResource(R.string.last_synced, DateFormat.format(pattern, Date(current.generatedAt)).toString()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            item { Text(stringResource(R.string.no_snapshot), textAlign = TextAlign.Center) }
                        }
                        if (!reachable)
                            item {
                                Text(stringResource(R.string.disconnected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        if (busy) item { Text(stringResource(R.string.saving), style = MaterialTheme.typography.bodySmall) }
                        status?.let { item { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) } }
                        if (pending != null) {
                            item {
                                FilledTonalButton(
                                    onClick = { scope.launch { model.retry() } },
                                    enabled = !busy && reachable,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.retry)) },
                                )
                            }
                            item {
                                OutlinedButton(
                                    onClick = model::dismissPending,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.dismiss)) },
                                )
                            }
                        }
                        item {
                            OutlinedButton(
                                onClick = { scope.launch { model.refresh() } },
                                enabled = !busy && reachable,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.refresh)) },
                            )
                        }
                    }
                }
            }
            AlertDialog(
                visible = endRequest != null,
                onDismissRequest = { endRequest = null },
                title = { Text(stringResource(R.string.end_confirm)) },
                confirmButton = {
                    AlertDialogDefaults.ConfirmButton(
                        onClick = {
                            val request = endRequest
                            endRequest = null
                            if (request != null) scope.launch { model.perform(request) }
                        }
                    )
                },
                dismissButton = { AlertDialogDefaults.DismissButton(onClick = { endRequest = null }) },
            )
        }
    }
}

/** The Sevens on the watch: cream ink on warm black, as the phone's dark scheme. Never purple. */
private val SevensWatchColors =
    androidx.wear.compose.material3.ColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFEEE9DF),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF161513),
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF36322D),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFEEE9DF),
        secondary = androidx.compose.ui.graphics.Color(0xFFA8A196),
        onSecondary = androidx.compose.ui.graphics.Color(0xFF121110),
        background = androidx.compose.ui.graphics.Color(0xFF000000),
        onBackground = androidx.compose.ui.graphics.Color(0xFFEEE9DF),
        onSurface = androidx.compose.ui.graphics.Color(0xFFEEE9DF),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFA8A196),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF1C1A18),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF24211D),
        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2B2723),
        outline = androidx.compose.ui.graphics.Color(0xFF8C857A),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF504A42),
        error = androidx.compose.ui.graphics.Color(0xFFFF7468),
        onError = androidx.compose.ui.graphics.Color(0xFF121110),
    )

/** Data hues colour data only (the goal ring, the water glyph), never controls. */
private val TimeHue = androidx.compose.ui.graphics.Color(0xFFF5913A)
private val WaterHue = androidx.compose.ui.graphics.Color(0xFF5EA8F2)
