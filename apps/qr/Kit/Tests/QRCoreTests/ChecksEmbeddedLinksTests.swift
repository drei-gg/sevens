import Foundation
import Testing
@testable import QRCore

@Suite struct ChecksEmbeddedLinksTests {
    @Test func checksEmbeddedFindsThreeShapes() {
        let t = "a http://one.example/x b HTTPS://Two.example/y c www.three.example/z d"
        #expect(EmbeddedLinks.find(in: t) == ["http://one.example/x", "HTTPS://Two.example/y", "www.three.example/z"])
    }

    @Test func checksEmbeddedDropsTrailingPunctuationAndStopsAtAngleAndQuote() {
        #expect(EmbeddedLinks.find(in: "(see https://a.example/p).") == ["https://a.example/p"])
        #expect(EmbeddedLinks.find(in: "[https://a.example/p],") == ["https://a.example/p"])
        #expect(EmbeddedLinks.find(in: "<https://a.example/p>") == ["https://a.example/p"])
        #expect(EmbeddedLinks.find(in: "href=\"https://a.example/p\"") == ["https://a.example/p"])
        #expect(EmbeddedLinks.find(in: "https://a.example/p...") == ["https://a.example/p"])
    }

    @Test func checksEmbeddedIgnoresBareSchemesAndWordsEndingInWWW() {
        #expect(EmbeddedLinks.find(in: "http:// and https://").isEmpty)
        #expect(EmbeddedLinks.find(in: "awww.example.com").isEmpty)
        #expect(EmbeddedLinks.find(in: "see www.example.com").count == 1)
        #expect(EmbeddedLinks.find(in: nil).isEmpty)
    }

    @Test func checksEmbeddedCapsAtTenAndDeduplicates() {
        let many = (0..<30).map { "https://e.example/\($0)" }.joined(separator: " ")
        #expect(EmbeddedLinks.find(in: many).count == 10)
        #expect(EmbeddedLinks.find(in: "https://e.example/a https://e.example/a") == ["https://e.example/a"])
    }

    @Test func checksEmbeddedIsLinearOnHostileInput() {
        let hostile = String(repeating: "http://", count: 3000) + String(repeating: "www.", count: 3000)
        #expect(EmbeddedLinks.find(in: hostile).count <= 10)
    }
}
