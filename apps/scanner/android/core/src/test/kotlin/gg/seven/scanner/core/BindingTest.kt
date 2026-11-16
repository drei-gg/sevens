package gg.seven.scanner.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** The crate's own suite proves the rules; this proves Kotlin reaches the crate. */
class BindingTest {
    @Test
    fun kotlinReachesTheCore() {
        assertTrue(coreVersion().matches(Regex("\\d+\\.\\d+\\.\\d+.*")))
    }
}
