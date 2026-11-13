package gg.seven.fast.core

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Port of Swift LiquidTests: geometry, full rotations, damping and sensor edge cases. */
class LiquidTest {
    @Test
    fun twoTurnsHaveNoSeamInEitherDirectionOrOrientation() {
        for (landscape in listOf(false, true)) for (direction in listOf(-1.0, 1.0)) {
            val s = LiquidSimulation()
            var previous = 0.0
            for (degrees in 0..720 step 10) {
                val a = degrees * PI / 180 * direction
                repeat(20) {
                    s.accelerometer(
                        9.8 * (if (landscape) cos(a) else sin(a)),
                        9.8 * (if (landscape) -sin(a) else cos(a)),
                        landscape,
                    )
                    s.step(0.02)
                    assertTrue(abs(s.angle - previous) < 0.15)
                    previous = s.angle
                }
            }
            repeat(150) {
                s.accelerometer(if (landscape) 9.8 else 0.0, if (landscape) 0.0 else 9.8, landscape)
                s.step(0.02)
            }
            assertEquals(direction * PI * 4, s.angle, 0.01)
            assertFalse(s.isMoving)
        }
    }

    @Test
    fun idleWavesKeepTiltAndWakeOnMotion() {
        val s = LiquidSimulation()
        repeat(500) {
            s.accelerometer(4.0, 9.0)
            s.step(1.0 / 60)
        }
        assertFalse(s.isMoving)
        val angle = s.angle
        val phase = s.phase
        s.step(1.0 / 60)
        assertEquals(angle, s.angle, 0.0)
        assertNotEquals(phase, s.phase)
        s.gyroscope(2.0)
        s.step(1.0 / 60)
        assertTrue(s.isMoving)
        assertTrue(s.ripple > 0)
    }

    @Test
    fun gyroDirectionAndInvalidSamplesAndLongFrames() {
        for (d in listOf(-1.0, 1.0)) {
            val s = LiquidSimulation()
            s.gyroscope(d)
            s.step(1.0 / 60)
            assertTrue(s.angle * d > 0)
        }
        val s = LiquidSimulation()
        s.accelerometer(Double.NaN, 9.8)
        s.gyroscope(Double.POSITIVE_INFINITY)
        s.step(Double.NaN)
        assertFalse(s.isMoving)
        repeat(100) {
            s.accelerometer(100.0, -100.0)
            s.gyroscope(100.0)
            s.step(10.0)
            assertTrue(s.angle.isFinite())
            assertTrue(s.ripple <= 9)
        }
        repeat(600) { s.step(1.0 / 60) }
        assertFalse(s.isMoving)
    }

    @Test
    fun circleKeepsAreaAtQuarterHalfAndOverfillBoundaries() {
        for (f in listOf(0.0, 0.15, 0.5, 0.85, 1.0)) {
            val offset = LiquidGeometry.circleSurfaceOffset(f)
            var inside = 0
            var filled = 0
            for (row in 0 until 200) for (col in 0 until 200) {
                val x = (col + 0.5) / 100 - 1
                val y = (row + 0.5) / 100 - 1
                if (x * x + y * y <= 1) {
                    inside++
                    if (y >= offset) filled++
                }
            }
            assertEquals(f, filled.toDouble() / inside, 0.01)
        }
        assertEquals(0.0, LiquidGeometry.circleSurfaceOffset(0.5), 0.0001)
        assertEquals(
            LiquidGeometry.circleSurfaceOffset(1.0),
            LiquidGeometry.circleSurfaceOffset(2.0),
            0.0,
        )
    }
}
