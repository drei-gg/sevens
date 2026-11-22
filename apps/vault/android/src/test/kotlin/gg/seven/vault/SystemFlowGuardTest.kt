package gg.seven.vault

import org.junit.Assert.*
import org.junit.Test

/** The lock's exception for system screens Vault itself opened: explicit, cleared, bounded. */
class SystemFlowGuardTest {
    private var now = 1_000L
    private val guard = SystemFlowGuard(timeoutMillis = 60_000) { now }

    @Test
    fun nothingInFlightLocksAtOnce() {
        assertFalse(guard.active)
        assertEquals(0, guard.remaining())
    }

    @Test
    fun aFlowInFlightDefersTheLockUntilItsBound() {
        guard.begin()
        now += 20_000
        assertTrue(guard.active)
        assertEquals(40_000, guard.remaining())
        now += 40_000
        assertFalse("the safety timeout ends the exception", guard.active)
        assertEquals(0, guard.remaining())
        now += 1
        assertEquals("and it stays ended", 0, guard.remaining())
    }

    @Test
    fun aResultOrReturnEndsIt() {
        guard.begin()
        now += 5_000
        guard.end()
        assertFalse(guard.active)
        assertEquals(0, guard.remaining())
    }

    @Test
    fun aNewFlowRestartsTheBound() {
        guard.begin()
        now += 50_000
        guard.begin()
        now += 50_000
        assertEquals(10_000, guard.remaining())
    }
}
