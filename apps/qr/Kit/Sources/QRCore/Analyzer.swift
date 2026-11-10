// Contract. WP 2.1 owns this file: Classifier + checks + verdict rule → Analysis.

public struct Analyzer: Sendable {
    public let data: BundledData
    let context: CheckContext

    public init(data: BundledData) {
        self.data = data
        self.context = CheckContext(data: data)
    }

    /// Offline, synchronous, < 5 ms typical.
    public func analyze(_ code: ScannedCode) -> Analysis {
        analyze(code, embedding: true)
    }

    /// Embedded links are analysed once, one level deep: a link found inside a link's text
    /// never produces further embedded analyses.
    func analyze(_ code: ScannedCode, embedding: Bool) -> Analysis {
        let result = Classifier(ctx: context).classify(code)
        let signals = result.signals.inStableOrder()
        let embedded: [Analysis] = embedding
            ? Self.embeddedLinks(of: result.payload).map { analyze(ScannedCode(text: $0, symbology: code.symbology), embedding: false) }
            : []
        let level = ([level(for: signals)] + embedded.map(\.level)).max() ?? .normal
        return Analysis(code: code, payload: result.payload, signals: signals, embedded: embedded, level: level)
    }

    static func embeddedLinks(of payload: Payload) -> [String] {
        switch payload {
        case .text(let i): return i.links
        case .contact(let i): return i.links
        case .event(let i): return i.links
        case .sms(let i): return i.links
        case .email(let i): return i.links
        default: return []
        }
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
    static let fixturesEnabled = true
}
