import Foundation
import Observation
import QRCore

/// One per result sheet. Runs the Peek (fetch, then registration age, then the pure analysis)
/// for the original scan. The Gate that wraps the Peek button lives in the view, not here.
@Observable @MainActor
public final class PeekModel {
    public enum Stage: Sendable, Hashable { case fetching, checkingAge }

    public enum State: Sendable {
        case idle
        case running(stage: Stage)
        case finished(PeekReport)
        /// The original isn't a link, so there is nothing to Peek at.
        case failed
    }

    public typealias Analyze = @Sendable (Analysis, PeekCapture, Int?) -> PeekReport

    public private(set) var state: State = .idle
    /// What the fetch returned; set once the fetch is done (the view can show hops while the age is pending).
    public private(set) var capture: PeekCapture?
    /// Nil until looked up, and when unknown.
    public private(set) var ageDays: Int?

    public let original: Analysis
    private let client: PeekClient
    private let data: BundledData
    private let analyze: Analyze
    private let now: @Sendable () -> Date
    private var task: Task<Void, Never>?

    public init(original: Analysis, client: PeekClient, data: BundledData = .shared,
                now: @escaping @Sendable () -> Date = { Date() }, analyze: @escaping Analyze) {
        self.original = original
        self.client = client
        self.data = data
        self.now = now
        self.analyze = analyze
    }

    /// `data` is what `analyzer` was made with; the hops are parsed against the same lists.
    public convenience init(original: Analysis, client: PeekClient = PeekClient(), analyzer: PeekAnalyzer,
                            data: BundledData = .shared) {
        self.init(original: original, client: client, data: data) { original, capture, age in
            analyzer.report(original: original, capture: capture, ageDays: age.map(Int64.init))
        }
    }

    public var isRunning: Bool {
        if case .running = state { return true }
        return false
    }

    /// Does nothing while running or after a finished Peek.
    public func start() {
        guard case .idle = state else { return }
        guard case .link(let link) = original.payload else { state = .failed; return }
        state = .running(stage: .fetching)
        task = Task { [weak self] in await self?.run(link) }
    }

    /// Stops the work and returns to idle; a later `start()` begins afresh.
    public func cancel() {
        task?.cancel()
        task = nil
        if isRunning { state = .idle }
        capture = nil
        ageDays = nil
    }

    private func run(_ link: LinkInfo) async {
        let captured = await client.capture(link)
        guard !Task.isCancelled else { return }
        capture = captured
        var age: Int?
        if let last = captured.hops.last, last.status != nil, captured.stoppedBecause == .finished,
           let address = WebAddress.parse(last.url, psl: data.publicSuffixes) {
            state = .running(stage: .checkingAge)
            age = await client.registrationAge(for: address.link, data: data, now: now())
            guard !Task.isCancelled else { return }
        }
        ageDays = age
        let report = analyze(original, captured, age)
        state = .finished(report)
        task = nil
    }
}
