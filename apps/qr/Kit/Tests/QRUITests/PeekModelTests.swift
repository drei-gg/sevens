import Foundation
import Testing
import QRCore
@testable import QRUI

/// Captures what the model hands the analyzer.
final class PeekModelRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [(capture: PeekCapture, age: Int?)] = []
    func record(_ capture: PeekCapture, _ age: Int?) { lock.withLock { calls.append((capture, age)) } }
    var all: [(capture: PeekCapture, age: Int?)] { lock.withLock { calls } }
}

final class PeekKnownRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [KnownAddresses] = []
    func record(_ known: KnownAddresses) { lock.withLock { calls.append(known) } }
    var all: [KnownAddresses] { lock.withLock { calls } }
}

@MainActor
@Suite struct PeekModelTests {
    nonisolated static let report = PeekReport(finalLink: nil, hopAnalyses: [], title: "T", signals: [], level: .careful)

    static func original(_ text: String) throws -> Analysis {
        let link = try PeekClientTests.link(text)
        return Analysis(code: ScannedCode(text: text), payload: .link(link), signals: [], embedded: [], level: .normal)
    }

    static func model(_ original: Analysis, recorder: PeekModelRecorder, base: String = "https://rdap-model.example.net/",
                      total: TimeInterval = 8, rdap: TimeInterval = 5) -> PeekModel {
        let client = PeekClient(protocolClasses: PeekClientTests.stub, totalTimeout: total, rdapTimeout: rdap, languages: ["en"])
        return PeekModel(original: original, client: client, data: PeekClientTests.bootstrap(base),
                         now: { PeekClientTests.now }) { _, capture, age in
            recorder.record(capture, age)
            return report
        }
    }

    static func wait(_ what: @MainActor () -> Bool) async -> Bool {
        for _ in 0..<600 {
            if what() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return what()
    }

    @Test func peekModelFetchesLooksUpTheAgeAndFinishes() async throws {
        PeekStubProtocol.register("https://model-ok.example.com/", .redirect(location: "https://model-final.example.com/"))
        PeekStubProtocol.register("https://model-final.example.com/", PeekClientTests.html())
        PeekStubProtocol.register("https://rdap-model.example.net/domain/example.com", .ok(body: PeekClientTests.rdapBody))
        PeekStubProtocol.register("https://rdap-model.example.net/domain/model-final.example.com", .ok(status: 404))
        let recorder = PeekModelRecorder()
        let model = Self.model(try Self.original("https://model-ok.example.com/"), recorder: recorder)
        guard case .idle = model.state else { Issue.record("not idle"); return }
        model.start()
        guard case .running(.fetching) = model.state else { Issue.record("not running"); return }
        let done = await Self.wait { if case .finished = model.state { return true } else { return false } }
        #expect(done)
        let call = try #require(recorder.all.first)
        #expect(recorder.all.count == 1)
        #expect(call.capture.stoppedBecause == .finished)
        #expect(call.capture.hops.map(\.url) == ["https://model-ok.example.com/", "https://model-final.example.com/"])
        // Final host is example.com's neighbour "model-final.example.com": registrable domain example.com.
        #expect(call.age == 10)
        #expect(model.ageDays == 10)
        #expect(model.capture?.hops.count == 2)
        if case .finished(let report) = model.state { #expect(report.title == "T") }
    }

    @Test func peekModelLooksUpTheScannedDomainsAgeWhenNoPageWasReached() async throws {
        // Redirected to plain http: the last hop that answered is the scanned one (SPEC §6.2).
        PeekStubProtocol.register("https://model-http.example.com/", .redirect(location: "http://plain.example.com/"))
        PeekStubProtocol.register("https://rdap-skip.example.net/domain/example.com", .ok(body: PeekClientTests.rdapBody))
        let recorder = PeekModelRecorder()
        let model = Self.model(try Self.original("https://model-http.example.com/"), recorder: recorder,
                               base: "https://rdap-skip.example.net/")
        model.start()
        let done = await Self.wait { if case .finished = model.state { return true } else { return false } }
        #expect(done)
        #expect(recorder.all.first?.capture.stoppedBecause == .httpTarget)
        #expect(recorder.all.first?.age == 10)
        #expect(!PeekStubProtocol.requests(to: "https://rdap-skip.example.net/domain/example.com").isEmpty)
    }

    @Test func peekModelKeepsTheAgeWhenTheFetchTimesOut() async throws {
        PeekStubProtocol.register("https://model-timeout.example.com/", .hang)
        PeekStubProtocol.register("https://rdap-timeout.example.net/domain/example.com", .ok(body: PeekClientTests.rdapBody))
        let recorder = PeekModelRecorder()
        let model = Self.model(try Self.original("https://model-timeout.example.com/"), recorder: recorder,
                               base: "https://rdap-timeout.example.net/", total: 0.8)
        model.start()
        let done = await Self.wait { if case .finished = model.state { return true } else { return false } }
        #expect(done)
        #expect(recorder.all.first?.capture.stoppedBecause == .timeout)
        #expect(recorder.all.first?.age == 10)
    }

    @Test func peekModelAsksAboutTheReachedDomainWhenItDiffers() async throws {
        PeekStubProtocol.register("https://model-cross.example.com/", .redirect(location: "https://other-site.com/"))
        PeekStubProtocol.register("https://other-site.com/", PeekClientTests.html())
        PeekStubProtocol.register("https://rdap-cross.example.net/domain/example.com", .ok(status: 404))
        PeekStubProtocol.register("https://rdap-cross.example.net/domain/other-site.com", .ok(body: PeekClientTests.rdapBody))
        let recorder = PeekModelRecorder()
        let model = Self.model(try Self.original("https://model-cross.example.com/"), recorder: recorder,
                               base: "https://rdap-cross.example.net/")
        model.start()
        let done = await Self.wait { if case .finished = model.state { return true } else { return false } }
        #expect(done)
        #expect(recorder.all.first?.capture.stoppedBecause == .finished)
        #expect(recorder.all.first?.age == 10)
        #expect(!PeekStubProtocol.requests(to: "https://rdap-cross.example.net/domain/other-site.com").isEmpty)
    }

    @Test func peekModelDoesNotFetchAPlainHTTPLinkButStillGetsItsAge() async throws {
        PeekStubProtocol.register("https://rdap-plain.example.net/domain/example.com", .ok(body: PeekClientTests.rdapBody))
        let recorder = PeekModelRecorder()
        let model = Self.model(try Self.original("http://model-plain.example.com/"), recorder: recorder,
                               base: "https://rdap-plain.example.net/")
        model.start()
        let done = await Self.wait { if case .finished = model.state { return true } else { return false } }
        #expect(done)
        let call = try #require(recorder.all.first)
        #expect(call.capture.stoppedBecause == .httpTarget)
        #expect(call.capture.hops.count == 1 && call.capture.hops[0].status == nil)
        #expect(call.age == 10)
        #expect(PeekStubProtocol.requests(to: "http://model-plain.example.com/").isEmpty)
        #expect(PeekViewText.notFetched(call.capture) != nil)
    }

    @Test func peekModelHandsTheKnownAddressesToTheAnalysis() async throws {
        PeekStubProtocol.register("https://model-known.example.com/", PeekClientTests.html())
        let seen = PeekKnownRecorder()
        let client = PeekClient(protocolClasses: PeekClientTests.stub, totalTimeout: 8, rdapTimeout: 1, languages: ["en"])
        let model = PeekModel(original: try Self.original("https://model-known.example.com/"), client: client,
                              data: PeekClientTests.bootstrap("https://rdap-known.example.net/"), now: { PeekClientTests.now },
                              analyzeKnown: { _, _, _, known in
            seen.record(known)
            return Self.report
        })
        var known = KnownAddresses()
        known.add("example.com", at: PeekClientTests.now)
        model.start(known: known)
        let done = await Self.wait { if case .finished = model.state { return true } else { return false } }
        #expect(done)
        #expect(seen.all == [known])
    }

    @Test func peekModelFailsForALinkWithNoPage() throws {
        let link = LinkInfo(original: "javascript:alert(1)", scheme: "javascript", hostASCII: "", hostUnicode: "",
                            pathAndQuery: "alert(1)", normalized: "javascript:alert(1)")
        let analysis = Analysis(code: ScannedCode(text: "javascript:alert(1)"), payload: .link(link), signals: [],
                                embedded: [], level: .likelyScam)
        let recorder = PeekModelRecorder()
        let model = Self.model(analysis, recorder: recorder)
        model.start()
        guard case .failed = model.state else { Issue.record("expected failed"); return }
        #expect(recorder.all.isEmpty)
    }

    @Test func peekModelShowsTheAgeStageThenFinishesWithUnknownAge() async throws {
        PeekStubProtocol.register("https://model-age.example.com/", PeekClientTests.html())
        PeekStubProtocol.register("https://rdap-slow.example.net/domain/example.com", .hang)
        let recorder = PeekModelRecorder()
        let model = Self.model(try Self.original("https://model-age.example.com/"), recorder: recorder,
                               base: "https://rdap-slow.example.net/", rdap: 0.6)
        model.start()
        let staged = await Self.wait { if case .running(.checkingAge) = model.state { return true } else { return false } }
        #expect(staged)
        #expect(model.capture != nil)
        let done = await Self.wait { if case .finished = model.state { return true } else { return false } }
        #expect(done)
        #expect(recorder.all.first?.age == nil)
    }

    @Test func peekModelFailsForAnOriginalThatIsNotALink() throws {
        let analysis = Analysis(code: ScannedCode(text: "hello"), payload: .text(TextInfo(text: "hello")), signals: [], embedded: [], level: .normal)
        let recorder = PeekModelRecorder()
        let model = Self.model(analysis, recorder: recorder)
        model.start()
        guard case .failed = model.state else { Issue.record("expected failed"); return }
        #expect(recorder.all.isEmpty)
    }

    @Test func peekModelCancelReturnsToIdleWithoutAnalyzing() async throws {
        PeekStubProtocol.register("https://model-cancel.example.com/", .hang)
        let recorder = PeekModelRecorder()
        let model = Self.model(try Self.original("https://model-cancel.example.com/"), recorder: recorder)
        model.start()
        #expect(model.isRunning)
        try? await Task.sleep(nanoseconds: 100_000_000)
        model.cancel()
        guard case .idle = model.state else { Issue.record("expected idle"); return }
        try? await Task.sleep(nanoseconds: 200_000_000)
        guard case .idle = model.state else { Issue.record("expected idle after cancel"); return }
        #expect(recorder.all.isEmpty)
    }
}
