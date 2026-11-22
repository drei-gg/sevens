package gg.seven.bloom

import android.content.Context
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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

@Composable
internal fun HealthFlowSettings(model: BloomModel) {
    val context = LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    val note by model.healthNote.collectAsStateWithLifecycle()
    val preview by model.healthPreview.collectAsStateWithLifecycle()
    var pending by rememberSaveable { mutableStateOf("") }
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    val supported = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    val denied = stringResource(R.string.health_denied)
    val grantedLocked = stringResource(R.string.health_granted_locked)
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
                model.healthNote.value = denied
            else if (model.appLock.locked.value)
                model.healthNote.value = grantedLocked
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
    val read = stringResource(R.string.health_read)
    val share = stringResource(R.string.health_share)
    val privacy = stringResource(R.string.health_privacy)
    Section(
        title = stringResource(R.string.health_title),
        footer =
            listOfNotNull(
                    stringResource(R.string.health_review_note),
                    stringResource(R.string.health_flow_note),
                    stringResource(R.string.health_unavailable).takeIf { !supported },
                    note,
                )
                .joinToString("\n\n"),
    ) {
        action(
            read,
            Icons.Outlined.Download,
            enabled = supported && !busy && pending.isEmpty(),
        ) {
            transfer("read")
        }
        action(
            share,
            Icons.Outlined.Upload,
            enabled = supported && !busy && pending.isEmpty(),
        ) {
            confirmShare = true
        }
        action(privacy, Icons.Outlined.PrivacyTip) {
            context.startActivity(
                android.content.Intent(context, HealthRationaleActivity::class.java)
            )
        }
    }
    if (confirmShare)
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text(stringResource(R.string.health_share_title)) },
            text = { Text(stringResource(R.string.health_share_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmShare = false
                        transfer("write")
                    }
                ) {
                    Text(stringResource(R.string.health_share_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmShare = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    preview?.let { reviewed ->
        val days =
            remember(reviewed) { JSONObject(reviewed).rows("days") }
                .map { pretty(it.getString("day")) to valueText(it.getString("flow")) }
        FullScreenForm(
            title = stringResource(R.string.health_review_title),
            onClose = { model.healthPreview.value = null },
            onSave = model::importHealth,
            busy = busy,
            saveLabel = stringResource(R.string.health_import),
        ) {
            Section(
                title = pluralStringResource(R.plurals.health_new_days, days.size, days.size),
                footer = stringResource(R.string.health_review_footer),
            ) {
                days.forEach { (day, flow) -> item(day, flow) }
            }
        }
    }
}

internal suspend fun readHealthFlow(context: Context): String =
    withContext(Dispatchers.IO) {
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
            ensure(count <= 50_000, R.string.health_too_many)
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
            ensure(page == null || seen.add(page!!), R.string.health_repeated_page)
        } while (page != null)
        samples.toString()
    }

internal suspend fun writeHealthFlow(context: Context, archive: String): Int =
    withContext(Dispatchers.IO) {
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
                context.resources.getQuantityString(R.plurals.health_stopped, accepted, accepted),
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
                        Text(
                            stringResource(R.string.health_records_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(stringResource(R.string.health_explanation))
                        Button(onClick = { finish() }) { Text(stringResource(R.string.action_done)) }
                    }
                }
            }
        }
    }
}
