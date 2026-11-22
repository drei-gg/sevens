package gg.seven.bloom.wire

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WireTest {
    private val now = 1_794_960_000L
    private val snapshot = WatchSnapshot(generated = now, revision = 4, enabled = true)

    @Test
    fun freshnessRejectsFutureYesterdayDisabledAndUnknownVersions() {
        val zone = ZoneId.of("UTC")
        assertTrue(snapshot.fresh(now, zone))
        assertFalse(snapshot.copy(generated = Long.MIN_VALUE).fresh(now, zone))
        assertFalse(snapshot.copy(generated = now + 1).fresh(now, zone))
        assertFalse(snapshot.copy(generated = now - 86_400).fresh(now, zone))
        assertFalse(snapshot.copy(enabled = false).fresh(now, zone))
        assertFalse(snapshot.copy(version = 2).fresh(now, zone))
        val midnight = now / 86_400 * 86_400
        assertFalse(snapshot.copy(generated = midnight - 1).fresh(midnight, zone))
    }

    @Test
    fun replacementRejectsOldAndFutureAndPrefersPrivacyAtEqualTime() {
        assertFalse(snapshot.copy(generated = now - 1).replaces(snapshot, now))
        assertFalse(snapshot.copy(generated = now + 61).replaces(snapshot, now))
        assertFalse(snapshot.copy(revision = 3).replaces(snapshot, now))
        val off = snapshot.copy(enabled = false, doses = emptyList())
        assertTrue(off.replaces(snapshot, now))
        assertFalse(snapshot.replaces(off, now))
        assertTrue(snapshot.copy(generated = now + 1).replaces(off, now + 1))
        assertTrue(snapshot.replaces(snapshot.copy(generated = now + 1000), now))
    }

    @Test
    fun boundedWireValidatesBothVersionsAndKeepsOnlyKnownFields() {
        assertThrows(IllegalArgumentException::class.java) {
            WatchCodec.decodeReply(ByteArray(512_001))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WatchCodec.stored("x".repeat(512_001))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WatchCodec.decodeReply(WatchCodec.encode(WatchReply(version = 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WatchCodec.decodeReply(
                WatchCodec.encode(WatchReply(snapshot = snapshot.copy(version = 2)))
            )
        }
        val extra =
            """{"snapshot":{"version":1,"generated":$now,"enabled":false,"notes":"private","contacts":["secret"]}}"""
        val stored =
            WatchCodec.encode(WatchStored(WatchCodec.decodeReply(extra.toByteArray()).snapshot))
        assertFalse(stored.contains("secret"))
        assertFalse(stored.contains("private"))
    }

    @Test
    fun doseCarriesRevisionAndStableReceiptIdentity() {
        val request =
            WatchRequest.dose(
                WatchDose("dose", "plan", "2026-11-18", now, "Reminder", "regular", "due"),
                "taken",
                4,
                now,
            )
        val decoded = WatchCodec.decodeRequest(WatchCodec.encode(request))
        assertEquals(request, decoded)
        assertEquals(request.id, request.command!!.request_id)
        assertEquals(4L, request.command.expected_revision)
        assertThrows(IllegalArgumentException::class.java) {
            WatchRequest.dose(
                request.command.record.let {
                    WatchDose(it.id, it.plan_id, it.day, it.scheduled, "Reminder", "regular", "due")
                },
                "deleted",
                4,
                now,
            )
        }
    }
}
