import Foundation
import Testing
@testable import QRCore

@Suite struct URLPublicSuffixTests {
    static let psl = BundledData.shared.publicSuffixes

    struct Vector: Sendable, CustomTestStringConvertible {
        var input: String
        var expected: String?
        var testDescription: String { input }
    }

    /// Every `checkPublicSuffix(...)` line of the list's own test file.
    static let vectors: [Vector] = {
        guard let dir = Fixtures.directory,
              let text = try? String(contentsOf: dir.appendingPathComponent("url/test_psl.txt"), encoding: .utf8) else { return [] }
        var out: [Vector] = []
        for line in text.split(separator: "\n") where line.hasPrefix("checkPublicSuffix(") {
            let inner = line.dropFirst("checkPublicSuffix(".count).dropLast(2)
            let parts = inner.components(separatedBy: ", ")
            func value(_ s: String) -> String? { s == "null" ? nil : String(s.dropFirst().dropLast()) }
            guard parts.count == 2 else { continue }
            guard let input = value(parts[0]) else { continue }   // null input: nothing to match
            out.append(Vector(input: input, expected: value(parts[1])))
        }
        return out
    }()

    @Test func vectorFileLoaded() {
        #expect(Self.vectors.count >= 70)
    }

    @Test("PSL test_psl.txt", arguments: vectors)
    func listOwnVector(v: Vector) {
        let host = URLHost.parse(v.input)?.ascii ?? ""
        let expected = v.expected.flatMap { URLHost.parse($0)?.ascii }
        #expect(Self.psl.match(hostASCII: host).registrableDomain == expected)
    }

    @Test func privateSuffix() {
        let m = Self.psl.match(hostASCII: "user.github.io")
        #expect(m.publicSuffix == "github.io")
        #expect(m.registrableDomain == "user.github.io")
        #expect(m.isPrivate)
        #expect(m.matchedExplicitRule)
        #expect(m.icannRegistrableDomain == "github.io")
    }

    @Test func icannSuffixIsNotPrivate() {
        let m = Self.psl.match(hostASCII: "www.bbc.co.uk")
        #expect(m.publicSuffix == "co.uk")
        #expect(m.registrableDomain == "bbc.co.uk")
        #expect(!m.isPrivate)
        #expect(m.icannRegistrableDomain == "bbc.co.uk")
    }

    @Test func hostThatIsASuffix() {
        let m = Self.psl.match(hostASCII: "co.uk")
        #expect(m.publicSuffix == "co.uk")
        #expect(m.registrableDomain == nil)
    }

    @Test func wildcardAndException() {
        let wild = Self.psl.match(hostASCII: "b.test.ck")
        #expect(wild.publicSuffix == "test.ck")
        #expect(wild.registrableDomain == "b.test.ck")
        let exc = Self.psl.match(hostASCII: "www.ck")
        #expect(exc.publicSuffix == "ck")
        #expect(exc.registrableDomain == "www.ck")
        #expect(exc.matchedExplicitRule)
    }

    @Test func defaultRuleIsNotExplicit() {
        let m = Self.psl.match(hostASCII: "a.example.zzzz")
        #expect(m.publicSuffix == "zzzz")
        #expect(m.registrableDomain == "example.zzzz")
        #expect(!m.matchedExplicitRule)
    }

    @Test func emptyListOnlyDefaultRule() {
        let empty = PublicSuffixList(text: "")
        let m = empty.match(hostASCII: "www.example.com")
        #expect(m.registrableDomain == "example.com")
        #expect(!m.matchedExplicitRule)
    }

    @Test func unicodeRulesAreMatchedInAscii() {
        let list = PublicSuffixList(text: "// ===BEGIN ICANN DOMAINS===\n公司.cn\n中国\n// ===END ICANN DOMAINS===\n")
        #expect(list.match(hostASCII: "xn--85x722f.xn--55qx5d.cn").registrableDomain == "xn--85x722f.xn--55qx5d.cn")
        #expect(list.match(hostASCII: "xn--55qx5d.cn").registrableDomain == nil)
    }

    @Test func privateSectionMarker() {
        let list = PublicSuffixList(text: "com\n// ===BEGIN PRIVATE DOMAINS===\nblogspot.com\n// ===END PRIVATE DOMAINS===\n")
        let m = list.match(hostASCII: "x.blogspot.com")
        #expect(m.isPrivate)
        #expect(m.icannRegistrableDomain == "blogspot.com")
        #expect(!list.match(hostASCII: "x.example.com").isPrivate)
    }
}
