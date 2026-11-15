import Foundation
import Testing
@testable import QRCore

struct MakeCompactionCase: Decodable, Sendable, CustomTestStringConvertible {
    var id: String
    var note: String
    var input: String
    var trimmed: String
    var compacted: String?
    var gains: Bool
    var testDescription: String { id }

    static let all: [MakeCompactionCase] = {
        guard let dir = Fixtures.directory,
              let data = try? Data(contentsOf: dir.appendingPathComponent("make/link-compaction.json")),
              let cases = try? JSONDecoder().decode([MakeCompactionCase].self, from: data) else { return [] }
        return cases
    }()
}

@Suite struct MakeLinkCompactorTests {
    static let psl = BundledData.shared.publicSuffixes
    static let list = [TrackingParam(name: "utm_*", source: "x"), TrackingParam(name: "fbclid", source: "x"),
                       TrackingParam(name: "gclid", source: "x")]

    @Test func fixturesLoad() {
        #expect(MakeCompactionCase.all.count >= 25)
        #expect(Set(MakeCompactionCase.all.map(\.id)).count == MakeCompactionCase.all.count)
    }

    @Test(arguments: MakeCompactionCase.all)
    func fixture(c: MakeCompactionCase) {
        #expect(LinkCompactor.trimmed(c.input) == c.trimmed)
        #expect(LinkCompactor.compacted(c.input) == c.compacted)
    }

    /// The compacted link is the same link: it parses to the same normalized text.
    @Test(arguments: MakeCompactionCase.all.filter { $0.compacted != nil })
    func compactedParsesToTheSameLink(c: MakeCompactionCase) throws {
        let before = try #require(WebAddress.parse(c.input, psl: Self.psl))
        let text = try #require(c.compacted)
        let after = try #require(WebAddress.parse(text, psl: Self.psl))
        #expect(after.link.normalized == before.link.normalized)
        #expect(after.link.hostASCII == before.link.hostASCII)
        #expect(after.link.registrableDomain == before.link.registrableDomain)
    }

    @Test func compactingTwiceChangesNothing() {
        for c in MakeCompactionCase.all {
            guard let once = LinkCompactor.compacted(c.input) else { continue }
            #expect(LinkCompactor.compacted(once) == once, "\(c.id)")
        }
    }

    @Test func hostileInputNeverTraps() {
        let odd = ["", "http://", "https://[", "https://[::1", "https://a:b:c/", "https:////x", "https:\\\\x\\y",
                   "https://@", "https://:443", "http://x:99999", String(repeating: "https://a.b/", count: 1000),
                   "https://example.com:443:443", "\u{0}https://x.y", "HTTPS://\u{FFFD}.com"]
        for s in odd {
            _ = LinkCompactor.trimmed(s)
            _ = LinkCompactor.compacted(s)
            _ = LinkCompactor.trackingParameters(in: s, list: Self.list)
            _ = LinkCompactor.removingTracking(from: s, list: Self.list)
        }
    }

    // MARK: M4

    @Test func findsTrackingParametersInOrder() {
        let link = "https://example.com/p?id=7&UTM_Source=a&fbclid=1&utm_x&keep=Yes&gclid=z"
        #expect(LinkCompactor.trackingParameters(in: link, list: Self.list) == ["UTM_Source", "fbclid", "utm_x", "gclid"])
    }

    @Test func removalKeepsTheRestVerbatim() {
        let link = "https://example.com/Path?id=7&utm_source=a&Keep=Yes%20No&fbclid=1#Frag"
        #expect(LinkCompactor.removingTracking(from: link, list: Self.list) == "https://example.com/Path?id=7&Keep=Yes%20No#Frag")
    }

    @Test func emptyQueryMarkGoes() {
        #expect(LinkCompactor.removingTracking(from: "https://example.com/a?utm_source=x&gclid=y", list: Self.list) == "https://example.com/a")
        #expect(LinkCompactor.removingTracking(from: "https://example.com/?fbclid=1#top", list: Self.list) == "https://example.com/#top")
    }

    @Test func nilWhenThereIsNothingToRemove() {
        for s in ["https://example.com/a?x=1", "https://example.com/a", "https://example.com/a#utm_source=1",
                  "https://example.com/a?", "mailto:a@b.c?utm_source=1", "https://example.com/a?utmx=1&xutm_source=2"] {
            #expect(LinkCompactor.removingTracking(from: s, list: Self.list) == nil, "\(s)")
            #expect(LinkCompactor.trackingParameters(in: s, list: Self.list).isEmpty, "\(s)")
        }
    }

    @Test func userinfoAndPortSurviveRemoval() {
        let r = LinkCompactor.removingTracking(from: "http://u:p@Example.com:8080/a?gclid=1&b=2", list: Self.list)
        #expect(r == "http://u:p@Example.com:8080/a?b=2")
    }

    @Test func emptyListFindsNothing() {
        #expect(LinkCompactor.removingTracking(from: "https://example.com/?utm_source=1", list: []) == nil)
    }
}
