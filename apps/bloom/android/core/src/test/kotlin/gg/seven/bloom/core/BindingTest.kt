package gg.seven.bloom.core

import org.junit.Assert.*
import org.junit.Test

class BindingTest {
    @Test
    fun kotlinRoundTripsTheSharedArchive() {
        val empty = bloomEmpty()
        assertEquals(empty, bloomValidate(empty))
        assertTrue(bloomDashboard(empty, 1794873600, "UTC").contains("today_doses"))
    }

    @Test
    fun backupsAreInteroperableAndAuthenticated() {
        val original = bloomEmpty()
        val sealed = bloomSeal(original, "a test passphrase with spaces")
        assertEquals(original, bloomOpen(sealed, "a test passphrase with spaces"))
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { bloomOpen(sealed, "a test passphrase with spaces") }
    }
}
