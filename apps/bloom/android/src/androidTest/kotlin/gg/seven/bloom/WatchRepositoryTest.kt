package gg.seven.bloom

import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchRepositoryTest {
    @Test
    fun scheduledDosePersistsPrivatelyAndRetriesExactlyOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "watch-test-${UUID.randomUUID()}"
        val repository = BloomRepository(context, name)
        try {
            val archive = JSONObject(repository.read())
            val profile =
                archive.getJSONObject("profile").put("watch_enabled", true).put("discreet", true)
            val enabled = JSONObject(repository.change(command("profile", profile, 0), false))
            val plan =
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("name", "Private medication")
                    .put("method", "pill")
                    .put("hormonal", true)
                    .put("start", LocalDate.now().toString())
                    .put("end", JSONObject.NULL)
                    .put("minute", 480)
                    .put("zone", ZoneId.systemDefault().id)
                    .put("fixed_zone", false)
                    .put("pattern", "daily")
                    .put("active_days", 21)
                    .put("break_days", 7)
                    .put("every_days", 1)
                    .put("weekdays", JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                    .put("remind_break", false)
                    .put("enabled", true)
                    .put("initial_stock", 21)
                    .put("notes", "Private notes sentinel")
            val current =
                JSONObject(
                    repository.change(command("plan", plan, enabled.getLong("revision")), true)
                )
            val projected = repository.watchSnapshot()
            assertFalse(projected.contains("Private medication"))
            assertFalse(projected.contains("Private notes"))
            val dose = JSONObject(projected).rows("doses").single()
            val record =
                JSONObject()
                    .put("id", dose.getString("id"))
                    .put("plan_id", dose.getString("plan_id"))
                    .put("day", dose.getString("day"))
                    .put("scheduled", dose.getLong("at"))
                    .put("status", "taken")
                    .put("recorded", Instant.now().epochSecond)
                    .put("notes", "")
            val request =
                JSONObject()
                    .put("version", 1)
                    .put("id", UUID.randomUUID().toString())
                    .put(
                        "command",
                        JSONObject(command("dose", record, current.getLong("revision"))),
                    )
                    .toString()
            val saved = repository.watchRequest(request)
            assertEquals(saved, repository.watchRequest(request))
            assertEquals(saved, BloomRepository(context, name).read())
            assertEquals(1, JSONObject(saved).rows("doses").size)
            assertFalse(repository.file.readText().contains("Private medication"))
            val off = JSONObject(saved).getJSONObject("profile").put("watch_enabled", false)
            repository.change(command("profile", off, JSONObject(saved).getLong("revision")), false)
            assertTrue(JSONObject(repository.watchSnapshot()).rows("doses").isEmpty())
            try {
                repository.watchRequest(request)
                fail("Disabled companion accepted action")
            } catch (_: gg.seven.bloom.core.Exception.Invalid) {}
        } finally {
            repository.file.delete()
        }
    }
}
