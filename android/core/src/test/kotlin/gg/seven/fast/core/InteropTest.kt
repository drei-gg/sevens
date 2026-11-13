package gg.seven.fast.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InteropTest {
    @Test
    fun readsActualSwiftExportAndWritesKotlinRoundTrip() {
        val text = checkNotNull(javaClass.getResource("/swift-schema1.json")).readText()
        val now = 1_794_571_200_000.0
        val swift = ArchiveCodec.decode(text, now)
        assertEquals(1, swift.fasts.size)
        assertEquals(1, swift.journal.size)
        assertEquals(275.5, swift.metrics.single().value, 0.0)
        assertEquals("A cross-platform record", swift.fasts.single().intention)
        assertEquals(300.0, swift.settings.water.quickAddMilliliters, 0.0)
        val output = ArchiveCodec.encode(swift)
        assertEquals(swift, ArchiveCodec.decode(output, now))
        File("build/interop/kotlin-schema1.json").apply {
            parentFile.mkdirs()
            writeText(output)
        }
    }
}
