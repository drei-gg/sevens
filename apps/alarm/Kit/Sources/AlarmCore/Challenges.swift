import Foundation

public struct SequenceChallenge: Sendable, Equatable {
    public let sequence: [Int]
    public private(set) var progress = 0
    public var complete: Bool { !sequence.isEmpty && progress == sequence.count }
    public init(sequence: [Int]) { self.sequence = sequence }
    @discardableResult public mutating func tap(_ tile: Int) -> Bool {
        guard !complete, !sequence.isEmpty else { return false }
        guard tile == sequence[progress] else { progress = 0; return false }
        progress += 1; return true
    }
}
public struct ShakeCounter: Sendable {
    public private(set) var count = 0
    private var armed = true
    private var lastPeak = -Double.infinity
    public init() {}
    public mutating func sample(acceleration: Double, timestamp: Double) {
        guard acceleration.isFinite, timestamp.isFinite else { return }
        if acceleration < 0.35 { armed = true }
        if armed && acceleration > 1.1 && timestamp - lastPeak >= 0.35 {
            count += 1; armed = false; lastPeak = timestamp
        }
    }
}
public enum ColorWord: String, CaseIterable, Sendable {
    case red, blue, green, orange
    public static func accepts(_ answer: ColorWord, ink: ColorWord) -> Bool { answer == ink }
}
