import SwiftUI
import CoreMotion
import Observation
import AlarmCore

struct MemoryMission: View {
    let level: Int
    let onComplete: () -> Void
    @State private var sequence = SequenceChallenge(sequence:[])
    @State private var highlighted: Int?
    @State private var showing = true
    @State private var replay = 0
    @State private var message = "Watch the tiles, then repeat the order."
    var body: some View {
        VStack(spacing:18) {
            Text(message).multilineTextAlignment(.center)
            LazyVGrid(columns:Array(repeating:GridItem(.flexible()),count:3)) {
                ForEach(0..<9,id:\.self) { tile in
                    Button {
                        if sequence.tap(tile) {
                            if sequence.complete { onComplete() }
                            else { message = "\(sequence.progress) of \(sequence.sequence.count)" }
                        } else { message = "Try that sequence again."; replay += 1 }
                    } label: { Text("\(tile + 1)").font(.title.bold()).frame(maxWidth:.infinity,minHeight:70).background(highlighted == tile ? Color.accentColor : Color.secondary.opacity(0.15),in:RoundedRectangle(cornerRadius:14)).foregroundStyle(highlighted == tile ? .white : .primary) }
                        .buttonStyle(.plain).disabled(showing || sequence.complete).accessibilityLabel("Tile \(tile + 1)").accessibilityIdentifier("memory-tile-\(tile)")
                }
            }
            Button("Show sequence again") { replay += 1 }.disabled(showing || sequence.complete)
        }.task(id:replay) {
            showing = true
            if sequence.sequence.isEmpty { sequence = SequenceChallenge(sequence:(0..<(level + 2)).map { _ in Int.random(in:0..<9) }) }
            else { sequence = SequenceChallenge(sequence:sequence.sequence) }
            do {
                try await Task.sleep(for:.milliseconds(600))
                for tile in sequence.sequence {
                    highlighted = tile
                    UIAccessibility.post(notification:.announcement,argument:"Tile \(tile + 1)")
                    try await Task.sleep(for:.milliseconds(850)); highlighted = nil
                    try await Task.sleep(for:.milliseconds(250))
                }
                showing = false; message = "Your turn. Repeat the order."
            } catch { highlighted = nil }
        }
    }
}
struct NumberOrderMission: View {
    let level: Int
    let onComplete: () -> Void
    @State private var tiles: [Int] = []
    @State private var next = 1
    @State private var message = "Tap the numbers from smallest to largest."
    var body: some View {
        VStack(spacing:18) {
            Text(message).multilineTextAlignment(.center)
            LazyVGrid(columns:Array(repeating:GridItem(.flexible()),count:3)) {
                ForEach(tiles,id:\.self) { number in
                    Button {
                        if number == next { next += 1; message = "Next: \(next)"; if next > tiles.count { onComplete() } }
                        else { message = "Find \(next) next." }
                    } label: { Text(number < next ? "✓" : "\(number)").font(.title.bold()).frame(maxWidth:.infinity,minHeight:64).background(Color.secondary.opacity(0.12),in:RoundedRectangle(cornerRadius:12)) }.buttonStyle(.plain).disabled(number < next).accessibilityLabel(number < next ? "Completed \(number)" : "Number \(number)").accessibilityIdentifier("order-\(number)")
                }
            }
        }.onAppear { if tiles.isEmpty { tiles = Array(1...(level * 3 + 3)).shuffled() } }
    }
}
extension ColorWord {
    var color: Color { switch self { case .red:.red; case .blue:.blue; case .green:.green; case .orange:.orange } }
    var label: String { rawValue.capitalized }
}
struct ColorWordMission: View {
    let level: Int
    let onComplete: () -> Void
    @State private var word = ColorWord.red
    @State private var ink = ColorWord.blue
    @State private var solved = 0
    @State private var message = "Choose the ink color, not the word."
    var body: some View {
        VStack(spacing:20) {
            Text("\(solved) of \(level + 2)").foregroundStyle(.secondary)
            Text(message).multilineTextAlignment(.center)
            Text(word.label.uppercased()).font(.largeTitle.weight(.black)).foregroundStyle(ink.color).accessibilityLabel("The word \(word.label), printed in \(ink.label)")
            LazyVGrid(columns:[GridItem(.flexible()),GridItem(.flexible())]) {
                ForEach(ColorWord.allCases,id:\.self) { answer in
                    Button(answer.label) {
                        if ColorWord.accepts(answer,ink:ink) { solved += 1; if solved >= level + 2 { onComplete() } else { randomize(); message = "Choose the ink color." } }
                        else { message = "Look at the ink, not the word." }
                    }.buttonStyle(.bordered).frame(minHeight:48).disabled(solved >= level + 2)
                }
            }
            Text("Color vision makes this one difficult? Choose a different mission in your alarm settings.").font(.footnote).foregroundStyle(.secondary)
        }.onAppear { randomize() }
    }
    func randomize() { ink = ColorWord.allCases.randomElement()!; word = ColorWord.allCases.filter {$0 != ink}.randomElement()! }
}
@Observable @MainActor final class MovementTracker {
    var count = 0
    var message = ""
    var running = false
    @ObservationIgnored private let motion = CMMotionManager()
    @ObservationIgnored private let pedometer = CMPedometer()
    @ObservationIgnored private var counter = ShakeCounter()
    @ObservationIgnored private var generation = UUID()
    func start(mode:MissionKind) {
        stop(); counter = ShakeCounter(); count = 0; message = ""; running = true
        let token = generation
        if mode == .walk {
            guard CMPedometer.isStepCountingAvailable() else { running = false; message = "Step counting is unavailable on this device."; return }
            pedometer.startUpdates(from:Date()) { [weak self] data,error in
                let steps = data?.numberOfSteps.intValue
                let failure = error?.localizedDescription
                Task { @MainActor in
                    guard let self, self.running, self.generation == token else { return }
                    if let failure { self.message = failure; self.stop() } else if let steps { self.count = steps }
                }
            }
        } else {
            guard motion.isDeviceMotionAvailable else { running = false; message = "Motion sensing is unavailable on this device."; return }
            motion.deviceMotionUpdateInterval = 0.05
            motion.startDeviceMotionUpdates(to:.main) { [weak self] data,error in
                let failure = error?.localizedDescription
                let a = data?.userAcceleration
                let magnitude = a.map { sqrt($0.x*$0.x + $0.y*$0.y + $0.z*$0.z) }
                let time = data?.timestamp
                Task { @MainActor in
                    guard let self, self.running, self.generation == token else { return }
                    if let failure { self.message = failure; self.stop() }
                    else if let magnitude, let time { self.counter.sample(acceleration:magnitude,timestamp:time); self.count = self.counter.count }
                }
            }
        }
    }
    func stop() { running = false; generation = UUID(); motion.stopDeviceMotionUpdates(); pedometer.stopUpdates() }
}
struct MovementMission: View {
    let mode: MissionKind
    let target: Int
    let onComplete: () -> Void
    @State private var tracker = MovementTracker()
    @State private var finished = false
    @Environment(\.scenePhase) private var phase
    var body: some View {
        VStack(spacing:20) {
            Image(systemName:mode == .walk ? "figure.walk" : "iphone.radiowaves.left.and.right").font(.largeTitle)
            Text("\(min(tracker.count,target)) / \(target)").font(.largeTitle.monospacedDigit())
            Text(mode == .walk ? "Take a short walk with your phone. Only steps after you start count." : "Hold your phone securely and shake it back and forth.").multilineTextAlignment(.center)
            ProgressView(value:Double(min(tracker.count,target)),total:Double(target))
            if !tracker.message.isEmpty { Text(tracker.message).foregroundStyle(.secondary) }
            if !tracker.running && !finished { Button("Start challenge") { tracker.start(mode:mode) }.buttonStyle(.borderedProminent) }
            Text("Motion stays on this phone and stops when you leave this challenge.").font(.footnote).foregroundStyle(.secondary)
        }.onChange(of:tracker.count) { _, count in if count >= target && !finished { finished = true; tracker.stop(); onComplete() } }
            .onChange(of:phase) { _, value in if value != .active { tracker.stop() } }
            .onDisappear { tracker.stop() }
    }
}
