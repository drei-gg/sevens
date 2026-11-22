import Foundation
import Observation
import QRCore

/// One per result sheet. Runs the Peek (the fetch with the scanned domain's registration age
/// alongside it, then the reached page's age if that is another domain, then the pure analysis)
/// for the original scan. The Gate that wraps the Peek button lives in the view, not here.
@Observable @MainActor
public final class PeekModel {
    public enum Stage: Sendable, Hashable { case fetching, checkingAge }

    public enum State: Sendable {
        case idle
        case running(stage: Stage)
        case finished(PeekReport)
        /// The original isn't a web link, so there is nothing to Peek at.
        case failed
    }

    public typealias Analyze = @Sendable (Analysis, PeekCapture, Int?) -> PeekReport
    /// `Analyze` that also gets the person's known addresses (SPEC §4.4), as given to `start(known:)`.
    public typealias AnalyzeKnown = @Sendable (Analysis, PeekCapture, Int?, KnownAddresses) -> PeekReport

    public private(set) var state: State = .idle
    /// What the fetch returned; set once the fetch is done (the view can show hops while the age is pending).
    public private(set) var capture: PeekCapture?
    /// Nil until looked up, and when unknown.
    public private(set) var ageDays: Int?

    public let original: Analysis
    private let client: PeekClient
    private let data: BundledData
    private let analyze: AnalyzeKnown
    private let now: @Sendable () -> Date
    private var task: Task<Void, Never>?

    public init(original: Analysis, client: PeekClient, data: BundledData = .shared,
                now: @escaping @Sendable () -> Date = { Date() }, analyzeKnown: @escaping AnalyzeKnown) {
        self.original = original
        self.client = client
        self.data = data
        self.now = now
        self.analyze = analyzeKnown
    }

    public convenience init(original: Analysis, client: PeekClient, data: BundledData = .shared,
                            now: @escaping @Sendable () -> Date = { Date() }, analyze: @escaping Analyze) {
        self.init(original: original, client: client, data: data, now: now, analyzeKnown: { original, capture, age, _ in
            analyze(original, capture, age)
        })
    }

    /// The app's Peek: every hop is checked against the lists in `data` and the known addresses
    /// given to `start(known:)`.
    public convenience init(original: Analysis, client: PeekClient = PeekClient(), data: BundledData = .shared) {
        self.init(original: original, client: client, data: data, analyzeKnown: { original, capture, age, known in
            PeekAnalyzer.withKnown(data: data, known: known)
                .report(original: original, capture: capture, ageDays: age.map(Int64.init))
        })
    }

    /// `data` is what `analyzer` was made with; the hops are parsed against the same lists. When
    /// `start(known:)` gets addresses, the hops are checked with them and the same lists instead.
    public convenience init(original: Analysis, client: PeekClient = PeekClient(), analyzer: PeekAnalyzer,
                            data: BundledData = .shared) {
        self.init(original: original, client: client, data: data, analyzeKnown: { original, capture, age, known in
            let peek = known.isEmpty ? analyzer : PeekAnalyzer.withKnown(data: data, known: known)
            return peek.report(original: original, capture: capture, ageDays: age.map(Int64.init))
        })
    }

    public var isRunning: Bool {
        if case .running = state { return true }
        return false
    }

    /// Does nothing while running or after a finished Peek. `known` is the person's known
    /// addresses, so a domain they said they know doesn't get its list-based signals back on a hop.
    public func start(known: KnownAddresses = KnownAddresses()) {
        guard case .idle = state else { return }
        guard case .link(let link) = original.payload, original.canPeek else { state = .failed; return }
        state = .running(stage: .fetching)
        task = Task { [weak self] in await self?.run(link, known: known) }
    }

    /// Stops the work and returns to idle; a later `start()` begins afresh.
    public func cancel() {
        task?.cancel()
        task = nil
        if isRunning { state = .idle }
        capture = nil
        ageDays = nil
    }

    private func run(_ link: LinkInfo, known: KnownAddresses) async {
        let client = client, data = data, at = now()
        // SPEC §6.2: the scanned domain's age is asked for while the page is fetched, so a slow,
        // failed or plain-http fetch still has it within the 8 seconds.
        async let scannedAge = client.registrationAge(for: link, data: data, now: at)
        let captured = await client.capture(link)
        guard !Task.isCancelled else { return }
        capture = captured
        var age: Int?
        if let target = PeekAge.link(original: original, capture: captured, data: data) {
            state = .running(stage: .checkingAge)
            let domain = PeekClient.rdapDomain(for: target, data: data)
            if domain != nil, domain == PeekClient.rdapDomain(for: link, data: data) {
                age = await scannedAge
            } else {
                age = await client.registrationAge(for: target, data: data, now: at)
            }
            guard !Task.isCancelled else { return }
        }
        ageDays = age
        let report = analyze(original, captured, age, known)
        state = .finished(report)
        task = nil
    }
}
