package gg.seven.fast.wire

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireCodecTest {
    private val id = UUID.fromString("0F8FAD5B-D9CB-469F-A165-70867728950E")
    private val revision = UUID.fromString("7C9E6679-7425-40DE-944B-E07FC1F90AE7")

    private fun malformed(bytes: ByteArray) {
        val e = assertThrows(WireException::class.java) { WireCodec.decodeRequest(bytes) }
        assertEquals(WireFailure.MALFORMED, e.failure)
    }

    @Test
    fun requestRoundTrip() {
        for (action in WireAction.entries) {
            val request = WireRequest.create(action, revision, id, now = 1_731_628_800_123, id = id)
            assertEquals(request, WireCodec.decodeRequest(WireCodec.encode(request)))
        }
        val bare = WireRequest.create(WireAction.REFRESH, now = 5)
        assertEquals(bare, WireCodec.decodeRequest(WireCodec.encode(bare)))
    }

    @Test
    fun replyRoundTrip() {
        val snapshot = WireSnapshot(revision, 1_731_628_800_000, id, 1_731_600_000_000, 1_731_657_600_000, 750.0, true)
        val replies =
            listOf(
                WireReply(requestId = id, snapshot = snapshot),
                WireReply(requestId = id, snapshot = snapshot.copy(activeId = null, start = null, goal = null), failure = WireFailure.STALE),
                WireReply(failure = WireFailure.MALFORMED),
            ) + WireFailure.entries.map { WireReply(requestId = id, failure = it) }
        for (reply in replies) assertEquals(reply, WireCodec.decodeReply(WireCodec.encode(reply)))
    }

    @Test
    fun fieldNamesMatchTheSwiftProtocol() {
        val text =
            WireCodec.encode(WireRequest.create(WireAction.WATER, revision, id, now = 42, id = id)).decodeToString()
        assertTrue(text, text.contains("\"version\":1"))
        assertTrue(text, text.contains("\"id\":\"0F8FAD5B-D9CB-469F-A165-70867728950E\""))
        assertTrue(text, text.contains("\"sentAt\":42"))
        assertTrue(text, text.contains("\"action\":\"water\""))
        assertTrue(text, text.contains("\"revision\":"))
        assertTrue(text, text.contains("\"activeID\":"))
        val reply =
            WireCodec.encode(WireReply(requestId = id, snapshot = WireSnapshot(revision, 1, null, null, null, 0.0, false), failure = WireFailure.SAVE_FAILED))
                .decodeToString()
        assertTrue(reply, reply.contains("\"requestID\":"))
        assertTrue(reply, reply.contains("\"failure\":\"saveFailed\""))
        assertTrue(reply, reply.contains("\"waterMilliliters\":0.0"))
        assertTrue(reply, reply.contains("\"canCreateRecords\":false"))
        // Absent optionals are omitted, as Swift's encodeIfPresent does.
        assertFalse(reply, reply.contains("activeID"))
        assertFalse(reply, reply.contains("null"))
    }

    @Test
    fun readsLowerCaseIdsAndIgnoresUnknownKeys() {
        val text = """{"version":1,"id":"0f8fad5b-d9cb-469f-a165-70867728950e","sentAt":7,"action":"end","extra":{"a":1}}"""
        val request = WireCodec.decodeRequest(text.encodeToByteArray())
        assertEquals(id, request.id)
        assertEquals(WireAction.END, request.action)
        assertNull(request.revision)
    }

    @Test
    fun unknownActionIsMalformed() {
        malformed("""{"version":1,"id":"$id","sentAt":7,"action":"delete"}""".encodeToByteArray())
    }

    @Test
    fun brokenInputIsMalformed() {
        malformed(byteArrayOf())
        malformed("not json".encodeToByteArray())
        malformed("""{"version":1,"id":"$id","action":"water"}""".encodeToByteArray()) // no sentAt
        malformed("""{"version":1,"id":"1-1-1-1-1","sentAt":7,"action":"water"}""".encodeToByteArray())
        malformed("""{"version":1,"id":"$id","sentAt":7.5,"action":"water"}""".encodeToByteArray())
        malformed(byteArrayOf(0x7b, 0xff.toByte(), 0x7d)) // invalid UTF-8
    }

    @Test
    fun newerVersionStillDecodesForTheUnsupportedReply() {
        val request = WireCodec.decodeRequest("""{"version":2,"id":"$id","sentAt":7,"action":"refresh"}""".encodeToByteArray())
        assertEquals(2, request.version)
    }

    @Test
    fun sizeLimit() {
        assertEquals(32_768, WireCodec.MAXIMUM_BYTES)
        val base = """{"version":1,"id":"$id","sentAt":7,"action":"water","pad":""}"""
        val fits = base.replace("\"pad\":\"\"", "\"pad\":\"" + "x".repeat(WireCodec.MAXIMUM_BYTES - base.length) + "\"")
        assertEquals(WireCodec.MAXIMUM_BYTES, fits.encodeToByteArray().size)
        assertEquals(WireAction.WATER, WireCodec.decodeRequest(fits.encodeToByteArray()).action)
        malformed((fits.dropLast(2) + "xx\"}").encodeToByteArray())
        val e = assertThrows(WireException::class.java) { WireCodec.decodeReply(ByteArray(WireCodec.MAXIMUM_BYTES + 1) { ' '.code.toByte() }) }
        assertEquals(WireFailure.MALFORMED, e.failure)
    }
}
