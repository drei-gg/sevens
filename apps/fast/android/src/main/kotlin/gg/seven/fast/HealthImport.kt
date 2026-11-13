package gg.seven.fast

import android.content.Context
import android.os.Build
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
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import gg.seven.fast.core.*
import gg.seven.kit.*
import gg.seven.kit.SevenTheme
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.*

internal const val healthExplanation =
    "Fast reads weight only when you request an import. It never writes Health Connect data, reads in the background or sends health records to a server. Imported weights become local records included in your backups. Revoke access in Health Connect whenever you want; delete imported records in Fast to remove the local copies."

@Composable
internal fun HealthImportPanel(m: FastModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<MetricEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val supported =
        Build.VERSION.SDK_INT >= 28 &&
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    val permissions = remember { setOf(HealthPermission.getReadPermission(WeightRecord::class)) }
    fun read() {
        if (working) return
        working = true
        error = null
        scope.launch {
            try {
                preview = readWeights(context)
            } catch (e: Exception) {
                error = "Could not read weight records: ${e.message}"
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
            else error = "Weight permission was not granted. Manual logging still works."
        }
    SevenGroup(
        header = "Health Connect · weight only",
        footer =
            listOfNotNull(
                    "Manual import of available records from the last 30 days. Existing samples are skipped, so importing again does not duplicate them.",
                    healthExplanation,
                    "Health Connect is not available on this device. You can still log measurements manually."
                        .takeIf { !supported },
                    "Start the trial or unlock to import new measurements.".takeIf { !m.canCreate },
                    error,
                )
                .joinToString("\n\n"),
    ) {
        ActionRow(
            if (working) "Reading weights…" else "Import weight from Health Connect",
            enabled = supported && !working && m.canCreate,
            leading = { Icon(Icons.Outlined.MonitorWeight, null) },
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
                    error = "Could not open Health Connect: ${e.message}"
                }
            }
        }
    }
    preview?.let { incoming ->
        val known = m.archive.value?.metrics?.mapNotNull { it.externalID }?.toSet().orEmpty()
        val count = incoming.count { it.externalID !in known }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Import $count weights?") },
            text = {
                Text(
                    "Existing samples and local edits are kept. The import is saved as one transaction."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        m.mutate(
                            { current ->
                                val existing = current.metrics.mapNotNull { it.externalID }.toSet()
                                current.copy(
                                    metrics =
                                        current.metrics +
                                            incoming.filter { it.externalID !in existing }
                                )
                            },
                            { preview = null },
                        )
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }
}

private suspend fun readWeights(context: Context): List<MetricEntry> =
    withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 28) { "Health Connect requires Android 9 or newer" }
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
                    MetricEntry(
                        date = it.time.toEpochMilli().toDouble(),
                        kind = MetricKind.weight,
                        value = it.weight.inKilograms,
                        externalID = "healthconnect:${it.metadata.id}",
                    )
                }
            require(result.size <= 100_000) { "Too many records in one import" }
            page = response.pageToken
        } while (page != null)
        result.distinctBy { it.externalID }
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
                        Text("Your health records", style = MaterialTheme.typography.headlineLarge)
                        Text(healthExplanation)
                        Button(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }
}
