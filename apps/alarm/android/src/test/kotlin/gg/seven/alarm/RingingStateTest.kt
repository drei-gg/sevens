package gg.seven.alarm

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ringing queue and the ringing screen's Open, without a device. */
class RingingStateTest {
    private fun alarm(name: String) = RingingNow(UUID.randomUUID(), name, Instant.now())

    @Test
    fun aSecondAlarmWaitsAndTheFirstKeepsRinging() {
        val queue = RingQueue()
        val first = alarm("Wake up")
        val second = alarm("Medication")
        assertTrue(queue.add(first))
        assertTrue(queue.add(second))
        assertEquals(first, queue.head.value)
        assertEquals(listOf(first, second), queue.items.value)
        assertTrue(second.id in queue)
    }

    @Test
    fun theSameAlarmGoingOffAgainKeepsItsPlace() {
        val queue = RingQueue()
        val first = alarm("Wake up")
        queue.add(first)
        queue.add(alarm("Medication"))
        assertFalse(queue.add(first.copy(since = Instant.now().plusSeconds(5))))
        assertEquals(first, queue.head.value)
        assertEquals(2, queue.items.value.size)
    }

    @Test
    fun stoppingTheFirstRingsTheNextAndEachIsStoppedOnItsOwn() {
        val queue = RingQueue()
        val first = alarm("Wake up")
        val second = alarm("Medication")
        val third = alarm("Walk")
        listOf(first, second, third).forEach { queue.add(it) }
        // A waiting alarm's own Stop leaves the ringing one alone.
        assertEquals(third, queue.remove(third.id))
        assertEquals(first, queue.head.value)
        assertEquals(first, queue.remove(first.id))
        assertEquals(second, queue.head.value)
        assertNull(queue.remove(first.id))
        queue.remove(second.id)
        assertNull(queue.head.value)
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun openOnAnUnlockedPhoneLaunchesAtOnce() {
        val id = UUID.randomUUID()
        val flow = OpenFlow()
        assertEquals(OpenFlow.Step.Launch(id), flow.open(id, locked = false))
        assertNull(flow.unlocking)
    }

    @Test
    fun openOnALockedPhoneKeepsRingingUntilTheUnlockSucceeds() {
        val id = UUID.randomUUID()
        val flow = OpenFlow()
        // Asking to unlock silences nothing: Launch (silence and open) comes only after success.
        assertEquals(OpenFlow.Step.Unlock(id), flow.open(id, locked = true))
        assertEquals(id, flow.unlocking)
        assertEquals(OpenFlow.Step.Wait, flow.open(id, locked = true))
        assertEquals(OpenFlow.Step.Launch(id), flow.unlocked())
        assertNull(flow.unlocking)
    }

    @Test
    fun aCancelledUnlockLeavesTheAlarmRingingAndOpenAvailable() {
        val queue = RingQueue()
        val ringing = alarm("Wake up")
        queue.add(ringing)
        val flow = OpenFlow()
        assertEquals(OpenFlow.Step.Unlock(ringing.id), flow.open(ringing.id, locked = true))
        flow.cancelled()
        assertNull(flow.unlocking)
        assertEquals(ringing, queue.head.value)
        assertEquals(OpenFlow.Step.Wait, flow.unlocked())
        // Open works again.
        assertEquals(OpenFlow.Step.Unlock(ringing.id), flow.open(ringing.id, locked = true))
    }
}
