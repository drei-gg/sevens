//! The water in the glass: a surface that follows gravity through full turns, sloshes when the
//! phone turns, and keeps a gentle wave when still. Ported from Drei's StarMiles fuel liquid; the
//! tuning numbers are the same. Sensors use that code's units: acceleration in m/s² with the
//! Android sign convention, rotation in rad/s.

use std::f64::consts::PI;
use std::sync::Mutex;

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct LiquidState {
    /// Surface tilt in radians; 0 is level with the phone upright.
    pub angle: f64,
    /// Slosh energy, 0…9.
    pub ripple: f64,
    /// Wave phase, 0…2π.
    pub phase: f64,
}

#[derive(Debug, Default)]
struct Inner {
    state: LiquidState,
    target: f64,
    velocity: f64,
    landscape_direction: Option<f64>,
}

impl Inner {
    fn moving(&self) -> bool {
        (self.target - self.state.angle).abs() > 0.0005 || self.velocity.abs() > 0.001 || self.state.ripple > 0.025
    }
}

/// One glass's simulation. Call `step` once per frame while visible; read `state` to draw.
#[derive(Debug, Default, uniffi::Object)]
pub struct LiquidSimulation {
    inner: Mutex<Inner>,
}

#[uniffi::export]
impl LiquidSimulation {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self::default()
    }

    pub fn state(&self) -> LiquidState {
        self.inner.lock().unwrap().state
    }

    pub fn is_moving(&self) -> bool {
        self.inner.lock().unwrap().moving()
    }

    pub fn accelerometer(&self, x: f64, y: f64, landscape: bool) {
        let mut s = self.inner.lock().unwrap();
        if !x.is_finite() || !y.is_finite() {
            return;
        }
        // Hold the last angle when almost flat: in-plane gravity is too weak.
        if x * x + y * y < 1.0 {
            return;
        }
        // Capture the screen's landscape quarter-turn once, not on every sample: changing it when
        // x changes sign would prevent an upside-down rotation.
        if landscape {
            if s.landscape_direction.is_none() {
                s.landscape_direction = Some(if x < 0.0 { -1.0 } else { 1.0 });
            }
        } else {
            s.landscape_direction = None;
        }
        let direction = s.landscape_direction.unwrap_or(1.0);
        let horizontal = if landscape { -y * direction } else { x };
        let vertical = if landscape { x * direction } else { y };
        let desired = horizontal.atan2(vertical);
        // Unwrap across ±π so a complete turn never snaps back the long way.
        let difference = desired - s.target;
        let delta = difference.sin().atan2(difference.cos());
        if delta.abs() < 0.004 {
            return;
        }
        s.target += delta * 0.2;
        s.state.ripple = (s.state.ripple + delta.abs() * 8.0).clamp(0.0, 9.0);
    }

    pub fn gyroscope(&self, z: f64) {
        let mut s = self.inner.lock().unwrap();
        if !z.is_finite() || z.abs() < 0.08 {
            return;
        }
        // Angular speed excites a little slosh; gravity supplies absolute tilt.
        s.velocity = (s.velocity + z.clamp(-3.0, 3.0) * 0.08).clamp(-4.0, 4.0);
        s.state.ripple = (s.state.ripple + z.abs().min(3.0) * 0.5).clamp(0.0, 9.0);
    }

    pub fn step(&self, seconds: f64) {
        let mut s = self.inner.lock().unwrap();
        if !seconds.is_finite() || seconds <= 0.0 {
            return;
        }
        // Substeps stay stable after slow frames; never catch up background time.
        let mut remaining = seconds.min(0.05);
        while remaining > 0.0 {
            let dt = remaining.min(1.0 / 120.0);
            s.velocity += ((s.target - s.state.angle) * 75.0 - s.velocity * 13.0) * dt;
            s.state.angle += s.velocity * dt;
            s.state.ripple *= (-2.6 * dt).exp();
            // Ease back to a slow idle wave as slosh dissipates.
            let wave_speed = 1.2 + 3.8 * (s.state.ripple / 9.0).clamp(0.0, 1.0);
            s.state.phase = (s.state.phase + dt * wave_speed) % (PI * 2.0);
            remaining -= dt;
        }
        if !s.moving() {
            s.state.angle = s.target;
            s.velocity = 0.0;
            s.state.ripple = 0.0;
        }
    }
}

/// Signed distance from a circle's centre to the water surface, along gravity and in units of
/// the radius (−1 the top, 1 the bottom), that leaves `fraction` of the circle's AREA below it. A
/// circle's segment area doesn't change with tilt, so this needs no angle. Bisection, 40 steps.
#[uniffi::export]
pub fn circle_surface_offset(fraction: f64) -> f64 {
    let filled = fraction.clamp(0.0, 1.0);
    let below = |d: f64| {
        let theta = 2.0 * d.clamp(-1.0, 1.0).acos();
        (theta - theta.sin()) / (2.0 * PI)
    };
    let (mut low, mut high) = (-1.0, 1.0);
    for _ in 0..40 {
        let mid = (low + high) / 2.0;
        if below(mid) > filled { low = mid } else { high = mid }
    }
    (low + high) / 2.0
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Cubic {
    pub control1: Point,
    pub control2: Point,
    pub to: Point,
}

/// A water layer's outline in a `width` × `height` box (points on iOS, dp on Android; origin top
/// left), before clipping to the glass. `Full` fills the box, `Empty` draws nothing.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum LiquidOutline {
    Empty,
    Full,
    /// Move to `start`, line to `surface_start`, the curves, line to `end`, close.
    Shape { start: Point, surface_start: Point, curves: Vec<Cubic>, end: Point },
}

/// The surface follows gravity (`state.angle`) and a sine wave whose height grows with slosh and
/// is damped toward empty and full. Cubic Hermite segments follow the sine and its tangent, so
/// slow sub-pixel motion stays smooth. `wave` offsets the phase per layer (0 is the crest, drawn
/// lower); `lift` raises the surface (the crest uses 5).
#[uniffi::export]
pub fn liquid_outline(width: f64, height: f64, fraction: f64, state: LiquidState, wave: u8, lift: f64) -> LiquidOutline {
    if width <= 0.0 || height <= 0.0 || fraction <= 0.0 {
        return LiquidOutline::Empty;
    }
    if fraction >= 1.0 {
        return LiquidOutline::Full;
    }
    let radius = width.max(height);
    let surface = circle_surface_offset(fraction) * width.min(height) / 2.0;
    let damping = (PI * fraction).sin();
    let (sine, cosine) = state.angle.sin_cos();
    let phase = state.phase + wave as f64;
    let (cx, cy) = (width / 2.0, height / 2.0);
    let rotate = |x: f64, y: f64| Point { x: cx + x * cosine - y * sine, y: cy + x * sine + y * cosine };
    let amplitude = (2.0 + state.ripple) * damping * if wave == 0 { 0.65 } else { 1.0 };
    let baseline = surface - lift * damping;
    let frequency = PI / radius;
    let height_at = |x: f64| baseline + ((x + radius) * frequency + phase).sin() * amplitude;
    let slope = |x: f64| ((x + radius) * frequency + phase).cos() * amplitude * frequency;
    let segments = 16;
    let step = 2.0 * radius / segments as f64;
    let curves = (0..segments)
        .map(|i| {
            let x = -radius + i as f64 * step;
            let next = x + step;
            Cubic {
                control1: rotate(x + step / 3.0, height_at(x) + slope(x) * step / 3.0),
                control2: rotate(next - step / 3.0, height_at(next) - slope(next) * step / 3.0),
                to: rotate(next, height_at(next)),
            }
        })
        .collect();
    LiquidOutline::Shape {
        start: rotate(-radius, radius),
        surface_start: rotate(-radius, height_at(-radius)),
        curves,
        end: rotate(radius, radius),
    }
}
