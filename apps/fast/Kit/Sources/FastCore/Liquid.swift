import Foundation

/// The water in the glass: a surface that follows gravity through full turns, sloshes when the
/// phone turns, and keeps a gentle wave when still. Ported from Drei's StarMiles fuel liquid
/// (`FuelLiquidSimulation`); the tuning numbers are the same. Sensor values use that code's units:
/// acceleration in m/s² with the Android/sensors_plus sign convention, rotation in rad/s.
public struct LiquidSimulation: Sendable {
    /// Surface tilt in radians; 0 is level with the phone upright.
    public private(set) var angle: Double = 0
    /// Slosh energy, 0...9.
    public private(set) var ripple: Double = 0
    /// Wave phase, 0..<2π.
    public private(set) var phase: Double = 0
    private var target: Double = 0
    private var velocity: Double = 0
    private var landscapeDirection: Double?

    public init() {}

    public var isMoving: Bool { abs(target - angle) > 0.0005 || abs(velocity) > 0.001 || ripple > 0.025 }

    public mutating func accelerometer(x: Double, y: Double, landscape: Bool) {
        guard x.isFinite, y.isFinite else { return }
        // Hold the last angle when almost flat: in-plane gravity is too weak.
        guard x * x + y * y >= 1 else { return }
        // Capture the screen's landscape quarter-turn once, not on every sample: changing it when
        // x changes sign would prevent an upside-down rotation.
        if landscape {
            if landscapeDirection == nil { landscapeDirection = x < 0 ? -1 : 1 }
        } else {
            landscapeDirection = nil
        }
        let horizontal = landscape ? -y * landscapeDirection! : x
        let vertical = landscape ? x * landscapeDirection! : y
        let desired = atan2(horizontal, vertical)
        // Unwrap across ±π so a complete turn never snaps back the long way.
        let difference = desired - target
        let delta = atan2(sin(difference), cos(difference))
        guard abs(delta) >= 0.004 else { return }
        target += delta * 0.2
        ripple = min(9, max(0, ripple + abs(delta) * 8))
    }

    public mutating func gyroscope(z: Double) {
        guard z.isFinite, abs(z) >= 0.08 else { return }
        // Angular speed excites a little slosh; gravity supplies absolute tilt.
        velocity = min(4, max(-4, velocity + min(3, max(-3, z)) * 0.08))
        ripple = min(9, max(0, ripple + min(3, abs(z)) * 0.5))
    }

    public mutating func step(_ seconds: Double) {
        guard seconds.isFinite, seconds > 0 else { return }
        // Substeps stay stable after slow frames; never catch up background time.
        var remaining = min(seconds, 0.05)
        while remaining > 0 {
            let dt = min(remaining, 1.0 / 120)
            velocity += ((target - angle) * 75 - velocity * 13) * dt
            angle += velocity * dt
            ripple *= exp(-2.6 * dt)
            // Ease back to a slow idle wave as slosh dissipates. Keep gravity's resting angle
            // rather than flattening the surface when motion stops.
            let waveSpeed = 1.2 + 3.8 * min(1, max(0, ripple / 9))
            phase = (phase + dt * waveSpeed).truncatingRemainder(dividingBy: .pi * 2)
            remaining -= dt
        }
        if !isMoving {
            angle = target
            velocity = 0
            ripple = 0
        }
    }
}

public enum LiquidGeometry {
    /// Signed distance from a circle's centre to the water surface, measured along gravity and
    /// in units of the radius (−1 the top, 1 the bottom), that leaves `fraction` of the circle's
    /// area below it. A circle's segment area doesn't change with tilt, so unlike StarMiles' card
    /// (a rectangle) this needs no angle. Solved by bisection on the segment-area formula.
    public static func circleSurfaceOffset(fraction: Double) -> Double {
        let filled = min(1, max(0, fraction))
        // Area fraction below a chord at distance d (positive toward the bottom).
        func below(_ d: Double) -> Double {
            let theta = 2 * acos(min(1, max(-1, d)))
            return (theta - sin(theta)) / (2 * .pi)
        }
        var low = -1.0, high = 1.0
        for _ in 0..<40 {
            let mid = (low + high) / 2
            if below(mid) > filled { low = mid } else { high = mid }
        }
        return (low + high) / 2
    }
}
