import SwiftUI
import CoreMotion
import FastCore
import SevenKit

/// A labelled number on a card: the summary tiles at the top of Trends and Water trends.
struct StatTile: View {
    let title: String
    let value: String
    var detail: String?
    var tint: Color?
    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            Text(title).textRole(.caption, emphasized: true).textCase(.uppercase)
            Text(value).foregroundStyle(tint ?? .primary).textRole(.section).numeric()
                .lineLimit(2).minimumScaleFactor(0.7)
            if let detail { Text(detail).textRole(.caption) }
        }
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
        .padding(Tokens.Space.ms)
        .background(Tokens.card, in: RoundedRectangle(cornerRadius: Tokens.Radius.card))
        .accessibilityElement(children: .combine)
    }
}

/// Two tiles a row, one a row at accessibility sizes.
struct StatGrid<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @ViewBuilder var content: Content
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.ms), count: textSize.isAccessibilitySize ? 1 : 2),
                  spacing: Tokens.Space.ms) { content }
    }
}

/// A list row that shows our own layout straight on paper, without the row's card.
extension View {
    func bareListRow() -> some View {
        listRowBackground(Color.clear).listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
    }
}

/// Every switch in its system colours (green when on). `.sevenLook()` tints the app ink, and a
/// switch would take that tint: cream with a white thumb in dark mode, 1.2:1 (polish guide §3.2).
/// The same fix as Alarm's.
struct SystemSwitchStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Toggle(configuration).toggleStyle(.switch).tint(nil)
    }
}

/// The label of a capsule button for one-tap amounts: "+ 250 mL". Style the button with
/// `.quickAddChip()`.
struct ChipLabel: View {
    let title: String
    var systemImage = "plus"
    var body: some View {
        Label(title, systemImage: systemImage).textRole(.rowTitle, emphasized: true).numeric()
            .lineLimit(1).minimumScaleFactor(0.8).frame(maxWidth: .infinity, minHeight: 28)
    }
}

extension View {
    func quickAddChip() -> some View {
        modifier(SevenButtonStyle(kind: .bordered)).controlSize(.large).buttonBorderShape(.capsule)
    }
}

/// A round glass of water. `level` is the day's total over the goal: below 1 the glass fills
/// toward it. Past 1 it counts in laps: each further goal's worth rises from the bottom over a
/// full glass, alternating the light and deep tones (deep over light from 1×, light over deep
/// from 2×, and so on), so every drink moves the water however far past the goal. Without a goal
/// there's nothing to fill toward, so it shows a drop.
///
/// The water moves like StarMiles' fuel card (ported, see `LiquidSimulation`): the surface follows
/// gravity as the phone tilts, sloshes when it turns and keeps a slow wave when still. Reduce
/// Motion keeps it level and still.
struct WaterLevel: View {
    var level: Double?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.verticalSizeClass) private var verticalSize
    @Environment(\.scenePhase) private var scenePhase
    @State private var motion = LiquidMotion()

    var body: some View {
        ZStack {
            Circle().fill(Tokens.card)
            if let level, level > 0 {
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { timeline in
                    let state = reduceMotion ? LiquidSimulation() : motion.advance(to: timeline.date)
                    Canvas { context, size in
                        Self.draw(level: level, state: state, in: &context, size: size)
                    }
                }
                .clipShape(Circle())
            } else if level == nil {
                Image(systemName: "drop.fill").textRole(.hero).foregroundStyle(Tokens.dataCool)
            }
            Circle().stroke(Tokens.dataCool, lineWidth: 3)
        }
        .aspectRatio(1, contentMode: .fit)
        .onAppear { updateSensors() }
        .onDisappear { motion.stop() }
        .onChange(of: scenePhase) { updateSensors() }
        .onChange(of: reduceMotion) { updateSensors() }
        .onChange(of: level == nil) { updateSensors() }
        .onChange(of: verticalSize) { motion.landscape = verticalSize == .compact }
        .accessibilityHidden(true)
    }

    private func updateSensors() {
        motion.landscape = verticalSize == .compact
        if scenePhase == .active, !reduceMotion, level != nil { motion.start() } else { motion.stop() }
    }

    /// Below the goal, the water with its crest. Past it, a full glass in one tone and the current
    /// lap rising over it in the other, both on the same gravity and wave.
    private static func draw(level: Double, state: LiquidSimulation, in context: inout GraphicsContext, size: CGSize) {
        let rect = CGRect(origin: .zero, size: size)
        let lap = level < 1 ? 0 : Int(level.rounded(.down))
        let rise = min(1, max(0, level - Double(lap)))
        guard lap > 0 else {
            // A light crest just above the surface, as on the fuel card.
            context.fill(LiquidShape.path(in: rect, fraction: rise, state: state, layer: 0, surfaceLift: 5),
                         with: .color(.white.opacity(0.55)))
            context.fill(LiquidShape.path(in: rect, fraction: rise, state: state),
                         with: .color(Tokens.dataCool.opacity(0.85)))
            return
        }
        // The light tone is painted on card so it still reads light over the deep tone.
        func fill(_ path: Path, light: Bool) {
            if light {
                context.fill(path, with: .color(Tokens.card))
                context.fill(path, with: .color(Tokens.dataCool.opacity(0.45)))
            } else {
                context.fill(path, with: .color(Tokens.dataCool))
            }
        }
        let underIsLight = lap % 2 == 1
        fill(LiquidShape.path(in: rect, fraction: 1, state: state), light: underIsLight)
        if rise > 0 {
            fill(LiquidShape.path(in: rect, fraction: rise, state: state, layer: 2), light: !underIsLight)
        }
    }
}

/// The water's outline in a round glass: StarMiles' `FuelLiquidGeometry.liquidPath`, with the
/// surface placed by the circle's area (`LiquidGeometry.circleSurfaceOffset`) instead of the card's.
enum LiquidShape {
    static func path(in rect: CGRect, fraction: Double, state: LiquidSimulation, layer: Int = 1, surfaceLift: CGFloat = 0) -> Path {
        var path = Path()
        guard !rect.isEmpty, fraction > 0 else { return path }
        if fraction >= 1 { path.addRect(rect); return path }
        let radius = max(rect.width, rect.height)
        let surface = CGFloat(LiquidGeometry.circleSurfaceOffset(fraction: fraction)) * min(rect.width, rect.height) / 2
        let damping = CGFloat(sin(.pi * fraction))
        let cosine = CGFloat(cos(state.angle)), sine = CGFloat(sin(state.angle))
        let phase = CGFloat(state.phase) + CGFloat(layer)
        let center = CGPoint(x: rect.midX, y: rect.midY)
        func rotate(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: center.x + x * cosine - y * sine, y: center.y + x * sine + y * cosine)
        }
        let amplitude = (2 + CGFloat(state.ripple)) * damping * (layer == 0 ? 0.65 : 1)
        let baseline = surface - surfaceLift * damping
        let frequency = CGFloat.pi / radius
        func wave(_ x: CGFloat) -> CGFloat { baseline + sin((x + radius) * frequency + phase) * amplitude }
        func slope(_ x: CGFloat) -> CGFloat { cos((x + radius) * frequency + phase) * amplitude * frequency }
        path.move(to: rotate(-radius, radius))
        path.addLine(to: rotate(-radius, wave(-radius)))
        // Cubic Hermite segments follow the sine and its tangent, so slow sub-pixel motion stays smooth.
        let segments = 16
        let width = 2 * radius / CGFloat(segments)
        for index in 0..<segments {
            let x = -radius + CGFloat(index) * width
            let next = x + width
            path.addCurve(to: rotate(next, wave(next)),
                          control1: rotate(x + width / 3, wave(x) + slope(x) * width / 3),
                          control2: rotate(next - width / 3, wave(next) - slope(next) * width / 3))
        }
        path.addLine(to: rotate(radius, radius))
        path.closeSubpath()
        return path
    }
}

/// Feeds the phone's accelerometer and gyroscope into a `LiquidSimulation` while a glass is on
/// screen. StarMiles read sensors_plus, which reports iOS acceleration as −9.81 × CoreMotion's g,
/// so samples are converted to keep its tuning. No permission is needed for these sensors.
@MainActor final class LiquidMotion {
    private var simulation = LiquidSimulation()
    private var last: Date?
    private var manager: CMMotionManager?
    /// The phone is on its side (a compact height); the simulation reads gravity a quarter-turn round.
    var landscape = false

    /// Steps the water to `date` and returns where it is.
    func advance(to date: Date) -> LiquidSimulation {
        if let last { simulation.step(date.timeIntervalSince(last)) }
        last = date
        return simulation
    }

    func start() {
        guard manager == nil else { return }
        let sensors = CMMotionManager()
        guard sensors.isAccelerometerAvailable else { return }
        manager = sensors
        last = nil
        sensors.accelerometerUpdateInterval = 0.05
        sensors.gyroUpdateInterval = 0.05
        sensors.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            guard let data else { return }
            let x = -9.81 * data.acceleration.x, y = -9.81 * data.acceleration.y
            MainActor.assumeIsolated {
                guard let self else { return }
                self.simulation.accelerometer(x: x, y: y, landscape: self.landscape)
            }
        }
        if sensors.isGyroAvailable {
            sensors.startGyroUpdates(to: .main) { [weak self] data, _ in
                guard let data else { return }
                let z = data.rotationRate.z
                MainActor.assumeIsolated { self?.simulation.gyroscope(z: z) }
            }
        }
    }

    func stop() {
        manager?.stopAccelerometerUpdates()
        manager?.stopGyroUpdates()
        manager = nil
    }
}
