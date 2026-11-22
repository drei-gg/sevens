package gg.seven.scanner

import gg.seven.scanner.core.AnnotationStyle
import gg.seven.scanner.core.PageAnnotation
import gg.seven.scanner.core.PageCrop
import gg.seven.scanner.core.newScanPage
import gg.seven.scanner.core.pageSettingsDefault
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gesture and exact-bounds edits surface core refusals instead of crashing the editor. */
class AnnotationDraftTest {
    private fun draft(settings: gg.seven.scanner.core.PageSettings = pageSettingsDefault()) =
        AnnotationDraft(UUID.randomUUID(), newScanPage("Text").copy(adjustments = settings))

    @Test
    fun invalidBoundsReportFailureAndKeepTheDraft() {
        val draft = draft()
        draft.add(PageCrop(0.2, 0.2, 0.1, 0.4)) // right before left
        assertNotNull(draft.failure)
        assertTrue(draft.marks.isEmpty())
        assertFalse(draft.canUndo)
    }

    @Test
    fun invalidChangeReportsFailureAndKeepsHistory() {
        val draft = draft()
        draft.add(PageCrop(0.1, 0.1, 0.5, 0.5))
        val bad = PageAnnotation(PageCrop(0.0, 0.0, 1.5, 1.0), AnnotationStyle.HIGHLIGHT)
        assertFalse(draft.change(draft.marks + bad))
        assertNotNull(draft.failure)
        assertEquals(1, draft.marks.size)
        draft.undo()
        assertTrue(draft.marks.isEmpty())
    }

    @Test
    fun dragsToTheCropEdgeSucceedAtEveryRotation() {
        for (turns in 0L..3L) {
            val draft =
                draft(pageSettingsDefault().copy(quarterTurns = turns, crop = PageCrop(0.13, 0.27, 0.91, 1.0)))
            // A float drag converted to double, ending at the far edge, plus rounding just past it.
            draft.add(PageCrop(0.1f.toDouble(), 0.0, 1.0f.toDouble(), 1.0 + 1e-12))
            assertNull(draft.failure)
            assertEquals(1, draft.marks.size)
        }
    }
}
