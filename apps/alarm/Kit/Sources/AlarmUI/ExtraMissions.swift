import SwiftUI
import CoreMotion
import Observation
import AlarmCore
import SevenKit

/// Saves a step of the open mission (MissionScreen.record); a preview ignores it.
typealias RecordProgress = ((inout MissionProgress) -> Void) -> Void

struct MemoryMission: View {
    let level: Int
    let saved: MissionProgress?
    let record: RecordProgress
    let onComplete: () -> Void
    @State private var sequence = SequenceChallenge(sequence:[])
    @State private var highlighted: Int?
    @State private var showing = true
    @State private var completed = false
    @State private var replay = 0
    @State private var message = f("Watch the tiles, then repeat the order.")
    var body: some View {
        VStack(spacing:Tokens.Space.m) {
            Text(message).textRole(.body).multilineTextAlignment(.center)
            LazyVGrid(columns:Array(repeating:GridItem(.flexible(),spacing:Tokens.Space.s),count:3),spacing:Tokens.Space.s) {
                ForEach(0..<9,id:\.self) { tile in
                    Button {
                        if sequence.tap(tile) {
                            if sequence.complete() { completed = true; onComplete() }
                            else { message = "\(sequence.progress()) of \(sequence.sequence().count)" }
                        } else { message = f("Try that sequence again."); replay += 1 }
                    } label: { MissionTile(text:"\(tile + 1)",lit:highlighted == tile) }
                        .buttonStyle(.plain).disabled(showing || completed).accessibilityLabel("Tile \(tile + 1)").accessibilityIdentifier("memory-tile-\(tile)")
                }
            }
            Button(f("Show sequence again"),systemImage:"arrow.counterclockwise") { replay += 1 }.disabled(showing || completed)
        }.task(id:replay) {
            showing = true
            // The core object holds the progress; a replay starts a fresh one on the same tiles.
            if sequence.sequence().isEmpty {
                // A reopened mission replays the tiles it was showing.
                let kept = saved?.tiles ?? []
                let tiles = kept.count == level + 2 && kept.allSatisfy({ (0..<9).contains($0) }) ? kept : (0..<(level + 2)).map { _ in Int32.random(in:0..<9) }
                sequence = SequenceChallenge(sequence:tiles); record { $0.tiles = tiles }
            }
            else { sequence = SequenceChallenge(sequence:sequence.sequence()) }
            do {
                try await Task.sleep(for:.milliseconds(600))
                for tile in sequence.sequence().map(Int.init) {
                    highlighted = tile
                    UIAccessibility.post(notification:.announcement,argument:"Tile \(tile + 1)")
                    try await Task.sleep(for:.milliseconds(850)); highlighted = nil
                    try await Task.sleep(for:.milliseconds(250))
                }
                showing = false; message = f("Your turn. Repeat the order.")
            } catch { highlighted = nil }
        }
    }
}
struct NumberOrderMission: View {
    let level: Int
    let saved: MissionProgress?
    let record: RecordProgress
    let onComplete: () -> Void
    @State private var tiles: [Int] = []
    @State private var next = 1
    @State private var message = f("Tap the numbers from smallest to largest.")
    var body: some View {
        VStack(spacing:Tokens.Space.m) {
            Text(message).textRole(.body).multilineTextAlignment(.center)
            LazyVGrid(columns:Array(repeating:GridItem(.flexible(),spacing:Tokens.Space.s),count:3),spacing:Tokens.Space.s) {
                ForEach(tiles,id:\.self) { number in
                    Button {
                        if number == next { next += 1; message = "Next: \(next)"; if next > tiles.count { onComplete() } else { let done = next - 1; record { $0.solved = Int32(done) } } }
                        else { message = "Find \(next) next." }
                    } label: { MissionTile(text:number < next ? "✓" : "\(number)",lit:false,done:number < next) }.buttonStyle(.plain).disabled(number < next).accessibilityLabel(number < next ? "Completed \(number)" : "Number \(number)").accessibilityIdentifier("order-\(number)")
                }
            }
        }.onAppear {
            guard tiles.isEmpty else { return }
            let count = level * 3 + 3
            // A reopened mission keeps its board and the numbers already tapped; the last tap is
            // always made here, so reopening never completes it.
            if let kept = saved?.tiles.map(Int.init), kept.count == count, Set(kept) == Set(1...count) {
                tiles = kept; next = min(Int(saved?.solved ?? 0), count - 1) + 1
            } else { tiles = Array(1...count).shuffled() }
            let board = tiles.map(Int32.init); record { $0.tiles = board }
        }
    }
}
extension ColorWord {
    /// The game's own inks, picked to clear 4.5:1 on paper and card in both themes (light 5.7–6.8,
    /// dark 6.6–9.5). They are the content of the game, not accents or status.
    var color: Color {
        switch self {
        case .red: Self.ink(0xB0201A, 0xFF7468)
        case .blue: Self.ink(0x1F57B8, 0x7EADFF)
        case .green: Self.ink(0x1D6B33, 0x6CCB86)
        case .orange: Self.ink(0x8A4A00, 0xF2A33A)
        }
    }
    private static func ink(_ light: Int, _ dark: Int) -> Color {
        func ui(_ hex: Int) -> UIColor { UIColor(red:CGFloat((hex >> 16) & 0xFF)/255,green:CGFloat((hex >> 8) & 0xFF)/255,blue:CGFloat(hex & 0xFF)/255,alpha:1) }
        return Color(uiColor:UIColor { $0.userInterfaceStyle == .dark ? ui(dark) : ui(light) })
    }
    var label: String { rawValue.capitalized }
}
struct ColorWordMission: View {
    let level: Int
    let record: RecordProgress
    let onComplete: () -> Void
    @State private var word = ColorWord.red
    @State private var ink = ColorWord.blue
    @State private var solved: Int
    init(level: Int, saved: MissionProgress?, record: @escaping RecordProgress, onComplete: @escaping () -> Void) {
        self.level = level; self.record = record; self.onComplete = onComplete
        _solved = State(initialValue: min(Int(saved?.solved ?? 0), level + 1))
    }
    @State private var message = f("Choose the ink color, not the word.")
    var body: some View {
        VStack(spacing:Tokens.Space.m) {
            Text("\(solved) of \(level + 2)").textRole(.caption,emphasized:true).numeric()
            Text(message).textRole(.body).multilineTextAlignment(.center)
            Text(word.label.uppercased()).foregroundStyle(ink.color).textRole(.display).accessibilityLabel("The word \(word.label), printed in \(ink.label)")
            LazyVGrid(columns:[GridItem(.flexible(),spacing:Tokens.Space.s),GridItem(.flexible(),spacing:Tokens.Space.s)],spacing:Tokens.Space.s) {
                ForEach(ColorWord.allCases,id:\.self) { answer in
                    Button(answer.label) {
                        if ColorWord.accepts(answer,ink:ink) { solved += 1; if solved >= level + 2 { onComplete() } else { let done = solved; record { $0.solved = Int32(done) }; randomize(); message = f("Choose the ink color.") } }
                        else { message = f("Look at the ink, not the word.") }
                    }.quickAddChip().frame(minHeight:48).disabled(solved >= level + 2)
                }
            }
            Text(f("Color vision makes this one difficult? Choose a different mission in your alarm settings.")).textRole(.caption).multilineTextAlignment(.center)
        }.onAppear { randomize() }
    }
    func randomize() { ink = ColorWord.allCases.randomElement()!; word = ColorWord.allCases.filter {$0 != ink}.randomElement()! }
}
@Observable @MainActor final class MovementTracker {
    var count = 0
    /// What was counted before this start (an earlier start, or before the mission was left).
    @ObservationIgnored private var offset = 0
    init(count: Int = 0) { self.count = count }
    var message = ""
    var running = false
    @ObservationIgnored private let motion = CMMotionManager()
    @ObservationIgnored private let pedometer = CMPedometer()
    @ObservationIgnored private var counter = ShakeCounter()
    @ObservationIgnored private var generation = UUID()
    func start(mode:MissionKind) {
        stop(); counter = ShakeCounter(); offset = count; message = ""; running = true
        let token = generation
        if mode == .walk {
            guard CMPedometer.isStepCountingAvailable() else { running = false; message = f("Step counting is unavailable on this device."); return }
            pedometer.startUpdates(from:Date()) { [weak self] data,error in
                let steps = data?.numberOfSteps.intValue
                let failure = error?.localizedDescription
                Task { @MainActor in
                    guard let self, self.running, self.generation == token else { return }
                    if let failure { self.message = failure; self.stop() } else if let steps { self.count = self.offset + steps }
                }
            }
        } else {
            guard motion.isDeviceMotionAvailable else { running = false; message = f("Motion sensing is unavailable on this device."); return }
            motion.deviceMotionUpdateInterval = 0.05
            motion.startDeviceMotionUpdates(to:.main) { [weak self] data,error in
                let failure = error?.localizedDescription
                let a = data?.userAcceleration
                let magnitude = a.map { sqrt($0.x*$0.x + $0.y*$0.y + $0.z*$0.z) }
                let time = data?.timestamp
                Task { @MainActor in
                    guard let self, self.running, self.generation == token else { return }
                    if let failure { self.message = failure; self.stop() }
                    else if let magnitude, let time { self.counter.sample(acceleration:magnitude,timestamp:time); self.count = self.offset + Int(self.counter.count()) }
                }
            }
        }
    }
    func stop() { running = false; generation = UUID(); motion.stopDeviceMotionUpdates(); pedometer.stopUpdates() }
}
struct MovementMission: View {
    let mode: MissionKind
    let target: Int
    let record: RecordProgress
    let onComplete: () -> Void
    @State private var tracker: MovementTracker
    @State private var finished = false
    @State private var savedCount: Int
    init(mode: MissionKind, target: Int, saved: MissionProgress?, record: @escaping RecordProgress, onComplete: @escaping () -> Void) {
        self.mode = mode; self.target = target; self.record = record; self.onComplete = onComplete
        // Counted before the mission was left; the last one is always counted here.
        let start = min(Int(saved?.solved ?? 0), max(0, target - 1))
        _tracker = State(initialValue: MovementTracker(count: start)); _savedCount = State(initialValue: start)
    }
    /// Saves the count every few, and on leaving, rather than on every step.
    private func save(force: Bool = false) {
        let count = min(tracker.count, target)
        guard count != savedCount, force || count - savedCount >= 5 else { return }
        savedCount = count; record { $0.solved = Int32(count) }
    }
    @Environment(\.scenePhase) private var phase
    var body: some View {
        VStack(spacing:Tokens.Space.m) {
            Image(systemName:mode == .walk ? "figure.walk" : "iphone.radiowaves.left.and.right").textRole(.hero).foregroundStyle(Tokens.dataWarm).accessibilityHidden(true)
            HStack(alignment:.firstTextBaseline,spacing:Tokens.Space.xs) {
                Text("\(min(tracker.count,target))").textRole(.display).numeric().contentTransition(.numericText())
                Text("/ \(target)").textRole(.section).numeric()
            }.accessibilityElement(children:.combine).accessibilityLabel(mode == .walk ? "\(min(tracker.count,target)) of \(target) steps" : "\(min(tracker.count,target)) of \(target) shakes")
            Capsule().fill(Tokens.rule).frame(height:Tokens.Space.s)
                .overlay(alignment:.leading) { GeometryReader { g in Capsule().fill(Tokens.dataWarm).frame(width:g.size.width * min(1,Double(tracker.count) / Double(max(1,target)))) } }
                .accessibilityHidden(true)
            Text(mode == .walk ? f("Take a short walk with your phone. Only steps after you start count.") : f("Hold your phone securely and shake it back and forth.")).textRole(.body).multilineTextAlignment(.center)
            if !tracker.message.isEmpty { Text(tracker.message).textRole(.secondary) }
            if !tracker.running && !finished { PrimaryButton(title:f("Start challenge"),systemImage:"play.fill") { tracker.start(mode:mode) } }
            Text(f("Motion stays on this phone and stops when you leave this challenge.")).textRole(.caption).multilineTextAlignment(.center)
        }.onChange(of:tracker.count) { _, count in if count >= target && !finished { finished = true; tracker.stop(); onComplete() } else { save() } }
            .onChange(of:phase) { _, value in if value != .active { tracker.stop(); if !finished { save(force: true) } } }
            .onDisappear { tracker.stop(); if !finished { save(force: true) } }
    }
}

/// A square tile for the memory and number missions: card on paper, ink with its onInk label
/// while lit, muted once done.
struct MissionTile: View {
    let text: String
    var lit = false
    var done = false
    var body: some View {
        Text(text)
            .foregroundStyle(lit ? Tokens.onInk : done ? Tokens.muted : Color.primary)
            .textRole(.title).numeric()
            .frame(maxWidth:.infinity,minHeight:68)
            .background(lit ? Tokens.ink : Tokens.card,in:RoundedRectangle(cornerRadius:Tokens.Radius.card,style:.continuous))
            .overlay(RoundedRectangle(cornerRadius:Tokens.Radius.card,style:.continuous).stroke(Tokens.rule,lineWidth:1))
    }
}
