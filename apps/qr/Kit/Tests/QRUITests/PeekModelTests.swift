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

    @Test func peekModelSkipsTheAgeWhenTheFetchDidNotReachAPage() async throws {
        PeekStubProtocol.register("https://model-http.example.com/", .redirect(location: "http://plain.example.com/"))
        PeekStubProtocol.register("https://rdap-skip.example.net/domain/example.com", .ok(body: PeekClientTests.rdapBody))
        let recorder = PeekModelRecorder()
        let model = Self.model(try Self.original("https://model-http.example.com/"), recorder: recorder,
                               base: "https://rdap-skip.example.net/")
        model.start()
        let done = await Self.wait { if case .finished = model.state { return true } else { return false } }
        #expect(done)
        #expect(recorder.all.first?.capture.stoppedBecause == .httpTarget)
        #expect(recorder.all.first?.age == nil)
        #expect(PeekStubProtocol.requests(to: "https://rdap-skip.example.net/domain/example.com").isEmpty)
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
