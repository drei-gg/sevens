import Foundation
import Testing
@testable import QRCore

/// A pair from `Fixtures/peek/` or `Fixtures/peekreport/`: superset of the WP 1.6 shape.
struct PeekAnalyzerPair: Decodable, Sendable, CustomTestStringConvertible {
    struct Hop: Decodable, Sendable { var url: String; var status: Int? }
    struct Expect: Decodable, Sendable {
        var title: String?
        var signals: [String]
        var info: [String]?
        var level: Int?
        var finalHost: String?
        var hopLevels: [Int]?
    }
    var id: String
    var original: String
    var hops: [Hop]
    var stoppedBecause: String
    var contentType: String?
    var contentDisposition: String?
    var ageDays: Int?
    var expect: Expect
    var testDescription: String { id }

    var capture: (PeekCapture, [UInt8])? { nil }

    static func names(in folder: String) -> [String] {
        guard let dir = Fixtures.directory?.appendingPathComponent(folder),
              let all = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return [] }
        return all.filter { $0.hasSuffix(".json") }.map { folder + "/" + String($0.dropLast(5)) }.sorted()
    }

    static func load(_ path: String) throws -> (PeekAnalyzerPair, [UInt8]) {
        let dir = try #require(Fixtures.directory)
        let json = try Data(contentsOf: dir.appendingPathComponent(path + ".json"))
        let html = (try? Data(contentsOf: dir.appendingPathComponent(path + ".html"))) ?? Data()
        return (try JSONDecoder().decode(PeekAnalyzerPair.self, from: json), [UInt8](html))
    }
}

@Suite struct PeekAnalyzerPairTests {
    static let analyzer = Analyzer(data: BundledData.shared)
    static let peek = PeekAnalyzer(data: BundledData.shared)

    static func report(_ pair: PeekAnalyzerPair, body: [UInt8]) -> PeekReport {
        let capture = PeekCapture(hops: pair.hops.map { PeekHop(url: $0.url, status: $0.status) },
                                  stoppedBecause: StopReason(rawValue: pair.stoppedBecause) ?? .finished,
                                  contentType: pair.contentType, contentDisposition: pair.contentDisposition, body: body)
        let original = analyzer.analyze(ScannedCode(text: pair.original))
        return peek.report(original: original, capture: capture, ageDays: pair.ageDays)
    }

    @Test(arguments: PeekAnalyzerPair.names(in: "peek") + PeekAnalyzerPair.names(in: "peekreport"))
    func pairMatchesExpectation(path: String) throws {
        let (pair, body) = try PeekAnalyzerPair.load(path)
        let r = Self.report(pair, body: body)
        #expect(Set(r.signals.filter { $0.severity != .info }.map(\.id.rawValue)) == Set(pair.expect.signals), "\(pair.id) signals")
        #expect(Set(r.signals.map(\.id)).count == r.signals.count, "\(pair.id) has duplicate signals")
        if let info = pair.expect.info {
            #expect(Set(r.signals.filter { $0.severity == .info }.map(\.id.rawValue)) == Set(info), "\(pair.id) info")
        }
        #expect(r.title == pair.expect.title, "\(pair.id) title")
        if let level = pair.expect.level { #expect(r.level.rawValue == level, "\(pair.id) level") }
        if path.hasPrefix("peekreport") {
            #expect(r.finalLink?.hostUnicode == pair.expect.finalHost, "\(pair.id) final host")
        }
        if let hopLevels = pair.expect.hopLevels { #expect(r.hopAnalyses.map(\.level.rawValue) == hopLevels, "\(pair.id) hop levels") }
        #expect(r.hopAnalyses.count == pair.hops.count)
        #expect(r.signals == r.signals.inStableOrder())
    }

    @Test func everyPeekPairIsCovered() {
        #expect(PeekAnalyzerPair.names(in: "peek").count >= 11)
        #expect(PeekAnalyzerPair.names(in: "peekreport").count >= 40)
    }
}
