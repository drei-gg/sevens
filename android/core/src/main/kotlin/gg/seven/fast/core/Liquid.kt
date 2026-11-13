package gg.seven.fast.core

import kotlin.math.*

/**
 * Direct port of Swift FastCore/Liquid.swift (itself ported from StarMiles' fuel liquid). Sensor
 * units: Android acceleration m/s², angular speed rad/s.
 */
class LiquidSimulation {
    var angle = 0.0
        private set

    var ripple = 0.0
        private set

    var phase = 0.0
        private set

    private var target = 0.0
    private var velocity = 0.0
    private var landscapeDirection: Double? = null
    val isMoving
        get() = abs(target - angle) > 0.0005 || abs(velocity) > 0.001 || ripple > 0.025

    fun accelerometer(x: Double, y: Double, landscape: Boolean = false) {
        if (!x.isFinite() || !y.isFinite() || x * x + y * y < 1) return
        if (landscape) {
            if (landscapeDirection == null) landscapeDirection = if (x < 0) -1.0 else 1.0
        } else landscapeDirection = null
        val horizontal = if (landscape) -y * landscapeDirection!! else x
        val vertical = if (landscape) x * landscapeDirection!! else y
        val difference = atan2(horizontal, vertical) - target
        val delta = atan2(sin(difference), cos(difference))
        if (abs(delta) < 0.004) return
        target += delta * 0.2
        ripple = (ripple + abs(delta) * 8).coerceIn(0.0, 9.0)
    }

    fun gyroscope(z: Double) {
        if (!z.isFinite() || abs(z) < 0.08) return
        velocity = (velocity + z.coerceIn(-3.0, 3.0) * 0.08).coerceIn(-4.0, 4.0)
        ripple = (ripple + min(3.0, abs(z)) * 0.5).coerceIn(0.0, 9.0)
    }

    fun step(seconds: Double) {
        if (!seconds.isFinite() || seconds <= 0) return
        var remaining = min(seconds, 0.05)
        while (remaining > 0) {
            val dt = min(remaining, 1.0 / 120)
            velocity += ((target - angle) * 75 - velocity * 13) * dt
            angle += velocity * dt
            ripple *= exp(-2.6 * dt)
            val waveSpeed = 1.2 + 3.8 * (ripple / 9).coerceIn(0.0, 1.0)
            phase = (phase + dt * waveSpeed) % (PI * 2)
            remaining -= dt
        }
        if (!isMoving) {
            angle = target
            velocity = 0.0
            ripple = 0.0
        }
    }
}

object LiquidGeometry {
    /** The filled fraction is AREA, not height; the circle keeps its volume at every tilt. */
    fun circleSurfaceOffset(fraction: Double): Double {
        val filled = fraction.coerceIn(0.0, 1.0)
        var low = -1.0
        var high = 1.0
        repeat(40) {
            val mid = (low + high) / 2
            val theta = 2 * acos(mid.coerceIn(-1.0, 1.0))
            if ((theta - sin(theta)) / (2 * PI) > filled) low = mid else high = mid
        }
        return (low + high) / 2
    }
}
