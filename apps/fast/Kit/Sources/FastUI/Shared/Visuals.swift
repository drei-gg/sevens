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
                    let state = reduceMotion ? LiquidState(angle: 0, ripple: 0, phase: 0) : motion.advance(to: timeline.date)
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

    /// The core decides the layers (laps past the goal alternate tones) and each surface's outline;
    /// this only paints them. The light tone is laid on card so it reads light over the deep one.
    private static func draw(level: Double, state: LiquidState, in context: inout GraphicsContext, size: CGSize) {
        for layer in waterLayers(level: level) {
            let path = Path(liquidOutline(width: size.width, height: size.height, fraction: layer.fraction, state: state,
                                          wave: layer.wave, lift: layer.lifted ? 5 : 0), size: size)
            switch layer.tone {
            case .crest: context.fill(path, with: .color(.white.opacity(0.55)))
            case .shallow: context.fill(path, with: .color(Tokens.dataCool.opacity(0.85)))
            case .light:
                context.fill(path, with: .color(Tokens.card))
                context.fill(path, with: .color(Tokens.dataCool.opacity(0.45)))
            case .deep: context.fill(path, with: .color(Tokens.dataCool))
            }
        }
    }
}

extension Path {
    /// A water layer's outline from the core, in points.
    init(_ outline: LiquidOutline, size: CGSize) {
        self.init()
        func point(_ p: Point) -> CGPoint { CGPoint(x: p.x, y: p.y) }
        switch outline {
        case .empty: break
        case .full: addRect(CGRect(origin: .zero, size: size))
        case let .shape(start, surfaceStart, curves, end):
            move(to: point(start))
            addLine(to: point(surfaceStart))
            for curve in curves { addCurve(to: point(curve.to), control1: point(curve.control1), control2: point(curve.control2)) }
            addLine(to: point(end))
            closeSubpath()
        }
    }
}

/// Feeds the phone's accelerometer and gyroscope into a `LiquidSimulation` while a glass is on
/// screen. StarMiles read sensors_plus, which reports iOS acceleration as −9.81 × CoreMotion's g,
/// so samples are converted to keep its tuning. No permission is needed for these sensors.
@MainActor final class LiquidMotion {
    private let simulation = LiquidSimulation()
    private var last: Date?
    private var manager: CMMotionManager?
    /// The phone is on its side (a compact height); the simulation reads gravity a quarter-turn round.
    var landscape = false

    /// Steps the water to `date` and returns where it is.
    func advance(to date: Date) -> LiquidState {
        if let last { simulation.step(seconds: date.timeIntervalSince(last)) }
        last = date
        return simulation.state()
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
