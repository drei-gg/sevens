// Contract (PLAN §5.4). WP 2.1 owns this file: Classifier + checks + verdict rule → Analysis.

public struct Analyzer: Sendable {
    public let data: BundledData

    public init(data: BundledData) {
        self.data = data
    }

    /// Offline, synchronous, < 5 ms typical.
    public func analyze(_ code: ScannedCode) -> Analysis {
        fatalError("WP 2.1")
    }

    /// SPEC 4.1: any strong → likelyScam; three or more warnings → likelyScam;
    /// one or two warnings → careful; otherwise normal. Info signals don't count.
    public func level(for signals: [Signal]) -> Level {
        if signals.contains(where: { $0.severity == .strong }) { return .likelyScam }
        let warnings = signals.filter { $0.severity == .warning }.count
        if warnings >= 3 { return .likelyScam }
        if warnings >= 1 { return .careful }
        return .normal
    }

    /// The generic fixture runner (Tests/QRCoreTests/FixtureRunnerTests.swift) runs only when
    /// this is true. WP 2.1 sets it to true once `analyze` is implemented.
    static let fixturesEnabled = false
}
