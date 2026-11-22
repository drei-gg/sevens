package gg.seven.bloom.wear

import gg.seven.bloom.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WatchModelTest {
    private val now = 1_794_960_000L
    private val dose = WatchDose("dose", "plan", "2026-11-18", now, "Reminder", "regular", "due")
    private val snapshot =
        WatchSnapshot(generated = now, revision = 4, enabled = true, doses = listOf(dose))

    private class Disk(var value: String? = null) : WatchPersistence {
        var fail = false
        var gate: CompletableDeferred<Unit>? = null

        override fun read() = value

        override suspend fun save(value: String) {
            gate?.await()
            check(!fail)
            this.value = value
        }
    }

    private class Link : PhoneLink {
        val sent = mutableListOf<ByteArray>()
        var storedItems = emptyList<ByteArray>()
        var answer: (ByteArray) -> ByteArray = { error("offline") }

        override suspend fun request(bytes: ByteArray): ByteArray {
            sent += bytes
            return answer(bytes)
        }

        override suspend fun stored() = storedItems
    }

    private fun disk() = Disk(WatchCodec.encode(WatchStored(snapshot)))

    @Test
    fun offlineRetrySurvivesRestartAndSendsExactlyOneReceipt() = runTest {
        val disk = disk()
        val link = Link()
        val model = WatchModel(disk, link) { now }
        model.record(dose, "taken")
        val pending = model.state.value.pending!!
        assertTrue(model.message.value!!.contains("Not confirmed"))
        val restarted = WatchModel(disk, link) { now }
        link.answer = {
            WatchCodec.encode(
                WatchReply(
                    request_id = WatchCodec.decodeRequest(it).id,
                    snapshot = snapshot.copy(revision = 5),
                )
            )
        }
        restarted.sync()
        assertArrayEquals(link.sent[0], link.sent[1])
        assertEquals(pending.id, WatchCodec.decodeRequest(link.sent[1]).id)
        assertNull(restarted.state.value.pending)
        assertEquals(5L, restarted.state.value.snapshot!!.revision)
        assertNull(WatchModel(disk, link) { now }.state.value.pending)
    }

    @Test
    fun wrongAcknowledgementAndExplicitRejectionRetainPending() = runTest {
        val link = Link()
        val model = WatchModel(disk(), link) { now }
        model.record(dose, "taken")
        val pending = model.state.value.pending
        link.answer = {
            WatchCodec.encode(WatchReply(request_id = "another-request", snapshot = snapshot))
        }
        model.sync()
        assertEquals(pending, model.state.value.pending)
        link.answer = {
            WatchCodec.encode(
                WatchReply(
                    request_id = pending!!.id,
                    snapshot = snapshot,
                    error = "Phone changed. Review history.",
                )
            )
        }
        model.sync()
        assertEquals(pending, model.state.value.pending)
        assertEquals("Phone changed. Review history.", model.message.value)
    }

    @Test
    fun disabledSnapshotClearsPrivateStateAndOldPublicationCannotRestoreIt() = runTest {
        val disk = disk()
        val model = WatchModel(disk, Link()) { now }
        model.record(dose, "taken")
        model.receive(
            WatchCodec.encode(
                WatchReply(snapshot = WatchSnapshot(generated = now, enabled = false))
            )
        )
        model.receive(WatchCodec.encode(WatchReply(snapshot = snapshot)))
        assertNull(model.state.value.pending)
        assertFalse(model.state.value.snapshot!!.enabled)
        assertTrue(model.state.value.snapshot!!.doses.isEmpty())
        assertFalse(disk.value!!.contains("Reminder"))
    }

    @Test
    fun failedPersistenceNeverSendsOrClaimsSuccess() = runTest {
        val disk = disk()
        disk.fail = true
        val link = Link()
        val model = WatchModel(disk, link) { now }
        model.record(dose, "taken")
        assertNull(model.state.value.pending)
        assertTrue(link.sent.isEmpty())
        assertTrue(model.message.value!!.contains("Could not keep"))
    }

    @Test
    fun repeatedTapsDuringDiskWriteCannotReplacePendingAction() = runTest {
        val disk = disk()
        val gate = CompletableDeferred<Unit>()
        disk.gate = gate
        val link = Link()
        val model = WatchModel(disk, link) { now }
        val first = async { model.record(dose, "taken") }
        testScheduler.runCurrent()
        val second = async { model.record(dose, "skipped") }
        testScheduler.runCurrent()
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals("taken", model.state.value.pending!!.command!!.record.status)
        assertEquals(1, link.sent.size)
    }

    @Test
    fun staleOrUnknownDoseCannotCreateAnActionAndMalformedDataDoesNotBlockRetry() = runTest {
        val link = Link()
        val model =
            WatchModel(
                Disk(WatchCodec.encode(WatchStored(snapshot.copy(generated = now - 86_400)))),
                link,
            ) {
                now
            }
        model.record(dose, "taken")
        assertTrue(link.sent.isEmpty())
        model.receive(WatchCodec.encode(WatchReply(snapshot = snapshot)))
        model.record(dose.copy(id = "different"), "taken")
        assertTrue(link.sent.isEmpty())
        link.storedItems = listOf("malformed".toByteArray())
        link.answer = {
            WatchCodec.encode(
                WatchReply(request_id = WatchCodec.decodeRequest(it).id, snapshot = snapshot)
            )
        }
        model.record(dose, "taken")
        assertEquals(1, link.sent.size)
        assertNull(model.state.value.pending)
    }

    @Test
    fun failedQueueClearPreservesThePendingAction() = runTest {
        val disk = disk()
        val model = WatchModel(disk, Link()) { now }
        model.record(dose, "taken")
        val pending = model.state.value.pending
        disk.fail = true
        model.discardPending()
        assertEquals(pending, model.state.value.pending)
        assertEquals("Could not clear the pending action. It remains queued.", model.message.value)
    }
}
