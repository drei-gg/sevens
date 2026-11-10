import Foundation
import Testing
@testable import QRCore

/// One `<name>.html` + `<name>.json` pair from `Fixtures/peek/`.
struct PeekFixturePair: Decodable, Sendable, CustomTestStringConvertible {
    struct Hop: Decodable, Sendable { var url: String; var status: Int? }
    struct SniffExpect: Decodable, Sendable {
        var formActions: [String]?
        var scriptRedirect: Bool?
        var metaRefreshURL: String?
        var inputTypes: [String]?
        var scriptCount: Int?
    }
    struct Expect: Decodable, Sendable {
        var title: String?
        var signals: [String]
        var sniff: SniffExpect?
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

    static var directory: URL? { Fixtures.directory?.appendingPathComponent("peek") }

    static func names() -> [String] {
        guard let dir = directory,
              let all = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return [] }
        return all.filter { $0.hasSuffix(".json") }.map { String($0.dropLast(5)) }.sorted()
    }

    static func load(_ name: String) throws -> (PeekFixturePair, [UInt8]) {
        let dir = try #require(directory)
        let json = try Data(contentsOf: dir.appendingPathComponent("\(name).json"))
        let html = try Data(contentsOf: dir.appendingPathComponent("\(name).html"))
        return (try JSONDecoder().decode(PeekFixturePair.self, from: json), [UInt8](html))
    }
}

@Suite struct PeekFixturePairTests {
    @Test func pairsExist() {
        let names = PeekFixturePair.names()
        for required in ["plain", "login", "brand-mismatch-login", "cross-domain-form", "meta-refresh", "script-redirect",
                         "js-only-shell", "non-utf8", "card-form", "otp-form", "download"] {
            #expect(names.contains(required), "missing \(required)")
        }
    }

    @Test(arguments: PeekFixturePair.names())
    func sniffMatchesExpectation(name: String) throws {
        let (pair, html) = try PeekFixturePair.load(name)
        #expect(pair.id == "peek-\(name)")
        let r = HTMLSniffer.sniff(body: html, contentType: pair.contentType)
        #expect(r.title == pair.expect.title)
        guard let e = pair.expect.sniff else { return }
        if let v = e.formActions { #expect(r.formActions.map { $0 ?? "" } == v) }
        if let v = e.scriptRedirect { #expect(r.scriptRedirect == v) }
        if let v = e.scriptCount { #expect(r.scriptCount == v) }
        if let v = e.inputTypes { #expect(r.inputs.map { $0.type ?? "" } == v) }
        #expect(r.metaRefreshURL == e.metaRefreshURL)
    }

    @Test func jsOnlyShellIsShortWithAScript() throws {
        let (pair, html) = try PeekFixturePair.load("js-only-shell")
        let r = HTMLSniffer.sniff(body: html, contentType: pair.contentType)
        #expect(r.visibleTextLength < 200 && r.scriptCount >= 1)
        let (plainPair, plain) = try PeekFixturePair.load("plain")
        #expect(HTMLSniffer.sniff(body: plain, contentType: plainPair.contentType).visibleTextLength >= 200)
    }

    @Test func nonUTF8PairIsReallyNotUTF8() throws {
        let (_, html) = try PeekFixturePair.load("non-utf8")
        #expect(String(data: Data(html), encoding: .utf8) == nil)
    }
}
