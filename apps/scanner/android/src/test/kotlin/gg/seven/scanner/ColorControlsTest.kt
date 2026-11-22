package gg.seven.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorControlsTest {
    private fun px(c: Float, b: Float, gray: Boolean, r: Float, g: Float, bl: Float) = ColorControls.apply(ColorControls.matrix(c, b, gray), r, g, bl)

    @Test
    fun defaultsAreIdentity() {
        assertEquals(Triple(10f, 128f, 250f), px(1f, 0f, false, 10f, 128f, 250f))
    }

    @Test
    fun brightnessAddsThenContrastScalesAroundMidGrey() {
        assertEquals(255f * 0.5f + 64f, px(1f, 0.5f, false, 64f, 64f, 64f).first, 0.01f)
        // Contrast 2: 64 → (64 − 128) × 2 + 128 = 0; 192 → 255 (clamped).
        assertEquals(0f, px(2f, 0f, false, 64f, 64f, 64f).first, 0.01f)
        assertEquals(255f, px(2f, 0f, false, 192f, 192f, 192f).first, 0.01f)
    }

    @Test
    fun grayscaleUsesLuma() {
        val (r, g, b) = px(1f, 0f, true, 255f, 0f, 0f)
        assertEquals(0.2126f * 255f, r, 0.01f)
        assertEquals(r, g, 0.001f)
        assertEquals(r, b, 0.001f)
    }
}
