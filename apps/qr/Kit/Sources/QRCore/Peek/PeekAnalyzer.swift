// Contract (PLAN §5.4). WP 2.1 owns this file: hops + page → signals (PLAN §6.5).

public struct PeekAnalyzer: Sendable {
    public let data: BundledData

    public init(data: BundledData) {
        self.data = data
    }

    /// `ageDays` nil means the age is unknown (no RDAP service, 404, no registration event, timeout).
    public func report(original: Analysis, capture: PeekCapture, ageDays: Int?) -> PeekReport {
        fatalError("WP 2.1")
    }
}
