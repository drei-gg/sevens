package gg.seven.bloom

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.bloom.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HealthFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun reviewedImportPersistsAndRechecksEdits() = runBlocking {
        val repository = BloomRepository(context, "health-test-${UUID.randomUUID()}")
        try {
            val day = LocalDate.now().minusDays(1).toString()
            val samples =
                JSONArray()
                    .put(
                        JSONObject().put("day", day).put("flow", "heavy").put("period_start", true)
                    )
            val preview = bloomHealthReview(bloomEmpty(), samples.toString())
            val local = JSONObject(preview).rows("days").single().put("notes", "Keep my local edit")
            repository.change(command("day", local, 0), true)
            val saved = repository.importHealth(preview)
            assertEquals(
                "Keep my local edit",
                JSONObject(saved).rows("days").single().getString("notes"),
            )
            assertEquals(saved, repository.importHealth(preview))
            val fresh = BloomRepository(context, "health-roundtrip-${UUID.randomUUID()}")
            try {
                val imported = fresh.importHealth(preview)
                assertEquals(1, JSONObject(imported).rows("days").size)
                assertEquals("", JSONObject(imported).rows("days").single().getString("notes"))
                assertFalse(fresh.file.readText().contains("heavy"))
                assertEquals(imported, fresh.importHealth(preview))
            } finally {
                fresh.file.delete()
            }
        } finally {
            repository.file.delete()
        }
    }

    @Test
    fun healthConnectWritesAreIdempotentAndOwnSamplesAreNotReimported() = runBlocking {
        assertEquals(HealthConnectClient.SDK_AVAILABLE, HealthConnectClient.getSdkStatus(context))
        val read = HealthPermission.getReadPermission(MenstruationFlowRecord::class)
        val write = HealthPermission.getWritePermission(MenstruationFlowRecord::class)
        val client = HealthConnectClient.getOrCreate(context)
        val original = client.permissionController.getGrantedPermissions()
        val ids = mutableListOf<String>()
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, read)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, write)
            val day = LocalDate.now().minusDays(1)
            val samples =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("day", day.toString())
                            .put("flow", "medium")
                            .put("period_start", true)
                    )
            val fixture = JSONObject(bloomHealthReview(bloomEmpty(), samples.toString()))
            // Isolate test-owned provider rows, including from earlier interrupted test runs.
            val id = UUID.randomUUID().toString()
            fixture.rows("days").single().put("id", id)
            ids += "bloom.flow.$id"
            val archive = fixture.toString()
            assertEquals(1, writeHealthFlow(context, archive))
            assertEquals(1, writeHealthFlow(context, archive))
            val result =
                client.readRecords(
                    ReadRecordsRequest(
                        MenstruationFlowRecord::class,
                        TimeRangeFilter.between(
                            day.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                            Instant.now(),
                        ),
                    )
                )
            val own = result.records.filter { it.metadata.clientRecordId in ids }
            assertEquals(1, own.size)
            assertEquals(MenstruationFlowRecord.FLOW_MEDIUM, own.single().flow)
            fixture.put("revision", 1)
            fixture.rows("days").single().put("flow", "heavy")
            assertEquals(1, writeHealthFlow(context, fixture.toString()))
            val updated =
                client
                    .readRecords(
                        ReadRecordsRequest(
                            MenstruationFlowRecord::class,
                            TimeRangeFilter.between(
                                day.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                                Instant.now(),
                            ),
                        )
                    )
                    .records
                    .filter { it.metadata.clientRecordId in ids }
            assertEquals(1, updated.size)
            assertEquals(MenstruationFlowRecord.FLOW_HEAVY, updated.single().flow)
            assertEquals(0, JSONArray(readHealthFlow(context)).length())
        } finally {
            if (ids.isNotEmpty())
                client.deleteRecords(
                    MenstruationFlowRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = ids,
                )
            // Permission revocation kills this instrumented process, so retain grants on the
            // disposable test device. The runner records/restores permission state outside it.
            println("Health permissions before fixture: $original")
        }
    }
}
