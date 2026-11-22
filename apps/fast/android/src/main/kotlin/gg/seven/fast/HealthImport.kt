package gg.seven.fast

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import gg.seven.kit.*
import gg.seven.kit.SevenTheme
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.*

@Composable
internal fun HealthImportPanel(m: FastModel) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<MetricEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val supported = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    val permissions = remember { setOf(HealthPermission.getReadPermission(WeightRecord::class)) }
    fun read() {
        if (working) return
        working = true
        error = null
        scope.launch {
            try {
                preview = readWeights(context)
            } catch (e: Exception) {
                error = res.getString(R.string.health_read_failed, e.message.orEmpty())
            } finally {
                working = false
            }
        }
    }
    val request =
        rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.containsAll(permissions)) read()
            else error = res.getString(R.string.health_permission_denied)
        }
    Section(
        title = stringResource(R.string.health_title),
        footer =
            listOfNotNull(
                    stringResource(R.string.health_manual_import),
                    stringResource(R.string.health_explanation),
                    stringResource(R.string.health_unavailable).takeIf { !supported },
                    stringResource(R.string.health_locked).takeIf { !m.canCreate },
                    error,
                )
                .joinToString("\n\n"),
    ) {
        action(
            res.getString(if (working) R.string.health_reading else R.string.health_import),
            Icons.Outlined.MonitorWeight,
            enabled = supported && !working && m.canCreate,
        ) {
            scope.launch {
                try {
                    val granted =
                        HealthConnectClient.getOrCreate(context)
                            .permissionController
                            .getGrantedPermissions()
                    if (granted.containsAll(permissions)) read()
                    else request.launch(permissions)
                } catch (e: Exception) {
                    error = res.getString(R.string.health_open_failed, e.message.orEmpty())
                }
            }
        }
    }
    val archive by m.archive.collectAsStateWithLifecycle()
    preview?.let { incoming ->
        val known = archive?.metrics?.mapNotNull { it.externalId }?.toSet().orEmpty()
        val count = incoming.count { it.externalId !in known }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(pluralStringResource(R.plurals.health_import_title, count, count)) },
            text = { Text(stringResource(R.string.health_import_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        m.mutate(
                            { current ->
                                val existing = current.metrics.mapNotNull { it.externalId }.toSet()
                                current.copy(
                                    metrics =
                                        current.metrics +
                                            incoming.filter { it.externalId !in existing }
                                )
                            },
                            { preview = null },
                        )
                    }
                ) {
                    Text(stringResource(R.string.health_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private suspend fun readWeights(context: Context): List<MetricEntry> =
    withContext(Dispatchers.IO) {
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val result = mutableListOf<MetricEntry>()
        var page: String? = null
        do {
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        WeightRecord::class,
                        timeRangeFilter =
                            TimeRangeFilter.between(now.minus(30, ChronoUnit.DAYS), now),
                        pageToken = page,
                    )
                )
            result +=
                response.records.map {
                    metricEntry(
                        date = it.time.toEpochMilli().toDouble(),
                        kind = MetricKind.WEIGHT,
                        value = it.weight.inKilograms,
                        externalId = "healthconnect:${it.metadata.id}",
                    )
                }
            require(result.size <= 100_000) { context.getString(R.string.health_too_many) }
            page = response.pageToken
        } while (page != null)
        result.distinctBy { it.externalId }
    }

class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SevenTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp).statusBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            stringResource(R.string.health_rationale_title),
                            style = MaterialTheme.typography.headlineLarge,
                        )
                        Text(stringResource(R.string.health_explanation))
                        Button(onClick = { finish() }) { Text(stringResource(R.string.done)) }
                    }
                }
            }
        }
    }
}
