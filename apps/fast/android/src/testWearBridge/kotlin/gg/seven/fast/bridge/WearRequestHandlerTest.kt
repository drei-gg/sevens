package gg.seven.fast.bridge

import gg.seven.fast.core.CompanionAction
import gg.seven.fast.core.CompanionFailure
import gg.seven.fast.core.CompanionRequest
import gg.seven.fast.wire.WireAction
import gg.seven.fast.wire.WireCodec
import gg.seven.fast.wire.WireFailure
import gg.seven.fast.wire.WireReply
import gg.seven.fast.wire.WireRequest
import gg.seven.fast.wire.WireSnapshot
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure JVM: no Rust library is loaded. The handler's store and core calls are stand-ins; the core's
// own tests cover watchApply's rules.
class WearRequestHandlerTest {
    private val snapshot = WireSnapshot(UUID.randomUUID(), 1, null, null, null, 0.0, true)
    private val applied = mutableListOf<CompanionRequest>()

    private fun handler(ready: Boolean = true, failure: Throwable? = null) =
        WearRequestHandler(
            ready = { ready },
            apply = {
                applied += it
                failure?.let { e -> throw e }
            },
            snapshot = { snapshot.takeIf { ready } },
        )

    private fun reply(handler: WearRequestHandler, bytes: ByteArray): WireReply = runBlocking { WireCodec.decodeReply(handler.handle(bytes)) }

    private fun reply(handler: WearRequestHandler, request: WireRequest) = reply(handler, WireCodec.encode(request))

    @Test
    fun everyCoreFailureMapsToItsWireFailure() {
        val cases =
            mapOf(
                CompanionFailure.Unsupported() to WireFailure.UNSUPPORTED,
                CompanionFailure.Malformed() to WireFailure.MALFORMED,
                CompanionFailure.Expired() to WireFailure.EXPIRED,
                CompanionFailure.Stale() to WireFailure.STALE,
                CompanionFailure.Locked() to WireFailure.LOCKED,
                CompanionFailure.Unavailable() to WireFailure.UNAVAILABLE,
                CompanionFailure.SaveFailed() to WireFailure.SAVE_FAILED,
                IOException("disk full") to WireFailure.SAVE_FAILED,
                IllegalStateException("read-only") to WireFailure.SAVE_FAILED,
            )
        for ((error, expected) in cases) {
            assertEquals(expected, wireFailure(error))
            val request = WireRequest.create(WireAction.WATER)
            val reply = reply(handler(failure = error), request)
            assertEquals(error.toString(), expected, reply.failure)
            assertEquals(request.id, reply.requestId)
            // A rejection still carries the current state, so the watch can show what changed.
            assertEquals(snapshot, reply.snapshot)
        }
    }

    @Test
    fun successRepliesWithAFreshSnapshot() {
        val revision = UUID.randomUUID()
        val active = UUID.randomUUID()
        val request = WireRequest.create(WireAction.END, revision, active, now = 1_700_000_000_123)
        val reply = reply(handler(), request)
        assertNull(reply.failure)
        assertEquals(request.id, reply.requestId)
        assertEquals(snapshot, reply.snapshot)
        val sent = applied.single()
        assertEquals(CompanionAction.END, sent.action)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_123), sent.sentAt)
        assertEquals(revision, sent.revision)
        assertEquals(active, sent.activeId)
        assertEquals(request.id, sent.id)
    }

    @Test
    fun actionsMapOneToOne() {
        for ((wire, core) in listOf(WireAction.START to CompanionAction.START, WireAction.WATER to CompanionAction.WATER, WireAction.END to CompanionAction.END))
            assertEquals(core, WireRequest.create(wire).companion().action)
        assertEquals(CompanionAction.REFRESH, WireRequest.create(WireAction.REFRESH).companion().action)
    }

    @Test
    fun refreshNeverWrites() {
        val reply = reply(handler(), WireRequest.create(WireAction.REFRESH))
        assertNull(reply.failure)
        assertEquals(snapshot, reply.snapshot)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun notReadyIsUnavailable() {
        val reply = reply(handler(ready = false), WireRequest.create(WireAction.START))
        assertEquals(WireFailure.UNAVAILABLE, reply.failure)
        assertNull(reply.snapshot)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun undecodableRequestIsMalformedWithoutAnId() {
        val reply = reply(handler(), """{"version":1,"id":"${UUID.randomUUID()}","sentAt":1,"action":"delete"}""".encodeToByteArray())
        assertEquals(WireFailure.MALFORMED, reply.failure)
        assertNull(reply.requestId)
        assertTrue(applied.isEmpty())
        assertEquals(WireFailure.MALFORMED, reply(handler(), ByteArray(WireCodec.MAXIMUM_BYTES + 1)).failure)
    }

    @Test
    fun newerVersionIsUnsupported() {
        val request = WireRequest.create(WireAction.WATER).copy(version = 2)
        val reply = reply(handler(), request)
        assertEquals(WireFailure.UNSUPPORTED, reply.failure)
        assertEquals(request.id, reply.requestId)
        assertTrue(applied.isEmpty())
    }
}
