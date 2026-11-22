package gg.seven.bloom

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.bloom.core.bloomHealthExport
import gg.seven.kit.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val healthFlowExplanation =
    "Only light, medium and heavy menstrual flow is transferred, when you request it. Read and write permissions are separate. Notes, symptoms, spotting, tests, temperatures, medication and contact records stay in the journal. Health Connect has no period-start flag; those markers are not transferred. There is no background sync or server. Imported days become local records in your backups. Revoking access does not delete copies; manage Health Connect copies there and journal copies here."

@Composable
internal fun HealthFlowSettings(model: BloomModel) {
    val context = LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    val note by model.healthNote.collectAsStateWithLifecycle()
    val preview by model.healthPreview.collectAsStateWithLifecycle()
    var pending by rememberSaveable { mutableStateOf("") }
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    val supported =
        Build.VERSION.SDK_INT >= 28 &&
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    val request =
        rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            val operation = pending
            pending = ""
            val permission =
                if (operation == "write")
                    HealthPermission.getWritePermission(MenstruationFlowRecord::class)
                else HealthPermission.getReadPermission(MenstruationFlowRecord::class)
            if (permission !in granted)
                model.healthNote.value =
                    "Flow permission was not granted. Your journal is unchanged."
            else if (model.appLock.locked.value)
                model.healthNote.value =
                    "Permission granted. Unlock your journal and choose the transfer again."
            else if (operation == "write") model.shareHealth()
            else if (operation == "read") model.readHealth()
        }
    fun transfer(operation: String) {
        pending = operation
        request.launch(
            setOf(
                if (operation == "write")
                    HealthPermission.getWritePermission(MenstruationFlowRecord::class)
                else HealthPermission.getReadPermission(MenstruationFlowRecord::class)
            )
        )
    }
    Section(
        title = "Health Connect · flow only",
        footer =
            listOfNotNull(
                    "Review available records from the last 30 days. Existing journal dates, including timed observations, are skipped.",
                    "No automatic sync. Only light, medium and heavy flow is transferred; notes and contacts stay here. Read and write permissions are separate.",
                    "Health Connect is unavailable here. Manual logging still works."
                        .takeIf { !supported },
                    note,
                )
                .joinToString("\n\n"),
    ) {
        action(
            "Read flow for review",
            Icons.Outlined.Download,
            enabled = supported && !busy && pending.isEmpty(),
        ) {
            transfer("read")
        }
        action(
            "Share journal flow",
            Icons.Outlined.Upload,
            enabled = supported && !busy && pending.isEmpty(),
        ) {
            confirmShare = true
        }
        action("Privacy and permissions", Icons.Outlined.PrivacyTip) {
            context.startActivity(
                android.content.Intent(context, HealthRationaleActivity::class.java)
            )
        }
    }
    if (confirmShare)
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text("Share flow to Health Connect?") },
            text = {
                Text(
                    "Share logged light, medium and heavy flow from daily entries and timed observations. Notes and contacts stay here. Later journal deletions do not delete Health Connect copies."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmShare = false
                        transfer("write")
                    }
                ) {
                    Text("Share flow")
                }
            },
            dismissButton = { TextButton(onClick = { confirmShare = false }) { Text("Cancel") } },
        )
    preview?.let { reviewed ->
        val days = remember(reviewed) { JSONObject(reviewed).rows("days") }
        FullScreenForm(
            title = "Review flow",
            onClose = { model.healthPreview.value = null },
            onSave = model::importHealth,
            busy = busy,
            saveLabel = "Import",
        ) {
            Section(
                title = if (days.size == 1) "1 new day" else "${days.size} new days",
                footer =
                    "Existing dates are checked again when saving. Your local entries and edits are kept.",
            ) {
                days.forEach { day ->
                    item(
                        day.getString("day"),
                        day.getString("flow").replaceFirstChar { it.uppercase() },
                    )
                }
            }
        }
    }
}

internal suspend fun readHealthFlow(context: Context): String =
    withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 28) { "Health Connect requires Android 9 or newer." }
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val samples = JSONArray()
        var page: String? = null
        val seen = mutableSetOf<String>()
        var count = 0
        do {
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        MenstruationFlowRecord::class,
                        timeRangeFilter =
                            TimeRangeFilter.between(now.minus(30, ChronoUnit.DAYS), now),
                        pageToken = page,
                    )
                )
            count += response.records.size
            check(count <= 50_000) {
                "Too many Health entries to review at once. Nothing was imported."
            }
            response.records.forEach { record ->
                if (record.metadata.dataOrigin.packageName == context.packageName) return@forEach
                val flow =
                    when (record.flow) {
                        MenstruationFlowRecord.FLOW_LIGHT -> "light"
                        MenstruationFlowRecord.FLOW_MEDIUM -> "medium"
                        MenstruationFlowRecord.FLOW_HEAVY -> "heavy"
                        else -> return@forEach
                    }
                val day =
                    record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toLocalDate()
                samples.put(
                    JSONObject()
                        .put("day", day.toString())
                        .put("flow", flow)
                        .put("period_start", false)
                )
            }
            page = response.pageToken?.takeIf { it.isNotEmpty() }
            check(page == null || seen.add(page!!)) {
                "Health Connect repeated a page. Nothing was imported; try again."
            }
        } while (page != null)
        samples.toString()
    }

internal suspend fun writeHealthFlow(context: Context, archive: String): Int =
    withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 28) { "Health Connect requires Android 9 or newer." }
        val client = HealthConnectClient.getOrCreate(context)
        val projected = JSONArray(bloomHealthExport(archive))
        val records =
            (0 until projected.length()).map { index ->
                val row = projected.getJSONObject(index)
                val zone =
                    if (row.isNull("zone")) ZoneId.systemDefault()
                    else ZoneId.of(row.getString("zone"))
                val at =
                    if (row.isNull("at"))
                        LocalDate.parse(row.getString("day")).atStartOfDay(zone).toInstant()
                    else Instant.ofEpochSecond(row.getLong("at"))
                MenstruationFlowRecord(
                    time = at,
                    zoneOffset = zone.rules.getOffset(at),
                    flow =
                        when (row.getString("flow")) {
                            "light" -> MenstruationFlowRecord.FLOW_LIGHT
                            "medium" -> MenstruationFlowRecord.FLOW_MEDIUM
                            else -> MenstruationFlowRecord.FLOW_HEAVY
                        },
                    metadata =
                        Metadata.manualEntry(
                            clientRecordId = row.getString("sync_id"),
                            clientRecordVersion = row.getLong("version"),
                        ),
                )
            }
        var accepted = 0
        try {
            for (batch in records.chunked(500)) {
                client.insertRecords(batch)
                accepted += batch.size
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Health Connect stopped after $accepted flow entries. Check flow permission and retry; stable record IDs prevent duplicate copies.",
                failure,
            )
        }
        accepted
    }

class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SevenTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(Space.l),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Text("Your health records", style = MaterialTheme.typography.headlineMedium)
                        Text(healthFlowExplanation)
                        Button(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }
}
