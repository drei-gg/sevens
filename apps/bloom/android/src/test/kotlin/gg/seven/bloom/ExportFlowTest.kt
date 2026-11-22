package gg.seven.bloom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pending-export state machine, without a device or a document provider. */
class ExportFlowTest {
    private class Memory : ExportMarker {
        var value: String? = null

        override fun read() = value

        override fun write(uri: String?) {
            value = uri
        }
    }

    private val bytes = "age-encryption.org/v1".toByteArray()

    @Test
    fun aPickedDocumentIsRecordedThenWrittenAndReleased() {
        val marker = Memory()
        val flow = ExportFlow(marker)
        assertEquals("bloom.age", flow.prepare(PendingExport("bloom.age", bytes)))
        assertTrue(flow.waiting)
        val step = flow.picked("content://doc/1")
        assertTrue(step is ExportFlow.Step.Write)
        step as ExportFlow.Step.Write
        assertEquals("content://doc/1", step.uri)
        assertArrayEquals(bytes, step.bytes)
        // Durable before the write starts, so a crash mid-write is cleaned up on the next start.
        assertEquals("content://doc/1", marker.value)
        assertFalse(flow.waiting)
        flow.finished("content://doc/1")
        assertNull(marker.value)
        assertEquals(ExportFlow.Step.None, flow.recover())
    }

    @Test
    fun aCancelledPickerLeavesNothingBehind() {
        val marker = Memory()
        val flow = ExportFlow(marker)
        flow.prepare(PendingExport("report.pdf", bytes))
        assertEquals(ExportFlow.Step.None, flow.picked(null))
        assertFalse(flow.waiting)
        assertNull(marker.value)
        // A late duplicate result has nothing to write.
        assertEquals(
            ExportFlow.Step.Discard("content://late", interrupted = true),
            flow.picked("content://late"),
        )
    }

    @Test
    fun aDocumentReturnedAfterTheBytesWereLostIsDeleted() {
        val marker = Memory()
        // A new process: the picker returns, but nothing was prepared in this one.
        val flow = ExportFlow(marker)
        assertEquals(
            ExportFlow.Step.Discard("content://doc/2", interrupted = true),
            flow.picked("content://doc/2"),
        )
        assertEquals("content://doc/2", marker.value)
        flow.finished("content://doc/2")
        assertNull(marker.value)
    }

    @Test
    fun anUnfinishedWriteIsDeletedOnTheNextStart() {
        val marker = Memory()
        ExportFlow(marker).apply {
            prepare(PendingExport("bloom.json", bytes))
            picked("content://doc/3")
            // The process dies here, before finished().
        }
        val next = ExportFlow(marker)
        assertEquals(ExportFlow.Step.Discard("content://doc/3", interrupted = false), next.recover())
        next.finished("content://doc/3")
        assertNull(marker.value)
    }

    @Test
    fun finishingAnOlderDocumentKeepsTheNewerRecord() {
        val marker = Memory()
        val flow = ExportFlow(marker)
        flow.prepare(PendingExport("a.csv", bytes))
        flow.picked("content://doc/a")
        flow.prepare(PendingExport("b.csv", bytes))
        flow.picked("content://doc/b")
        flow.finished("content://doc/a")
        assertEquals("content://doc/b", marker.value)
    }

    @Test
    fun preparingAgainReplacesTheUnpickedExport() {
        val flow = ExportFlow(Memory())
        flow.prepare(PendingExport("first.txt", "one".toByteArray()))
        flow.prepare(PendingExport("second.txt", "two".toByteArray()))
        val step = flow.picked("content://doc/4") as ExportFlow.Step.Write
        assertArrayEquals("two".toByteArray(), step.bytes)
    }
}
