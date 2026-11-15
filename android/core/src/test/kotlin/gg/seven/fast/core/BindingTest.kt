package gg.seven.fast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Rust core's own suite proves the rules; this proves Kotlin reaches it correctly. */
class BindingTest {
    @Test
    fun swiftWrittenArchiveRoundTripsThroughKotlin() {
        val swift = javaClass.getResource("/swift-schema1.json")!!.readText()
        val archive = ArchiveCodec.decode(swift, 1_900_000_000_000.0)
        assertEquals("Etc/UTC", archive.fasts.single().timeZoneId)
        assertEquals(1_794_551_199_876.544, archive.fasts.single().start.millis(), 0.001)
        assertEquals(swift, ArchiveCodec.encode(archive))
    }

    @Test
    fun typedErrorsAndFaçadeUnits() {
        val now = 1_800_000_000_000.0
        val a = FastArchive.empty().save(fastRecord(now - 3_600_000, now), now)
        val overlap = runCatching { a.save(fastRecord(now - 1_800_000, now), now) }.exceptionOrNull()
        assertTrue(overlap is TrackerException.Overlap)
        assertEquals("This overlaps another fast.", overlap!!.explanation)
        assertEquals(3_600_000.0, a.fasts.single().duration(now), 0.0)
        assertEquals(6, FastPreset.defaults.size)
        assertEquals(listOf(0.0), waterLayers(0.0).map { it.fraction }.ifEmpty { listOf(0.0) })
    }
}
