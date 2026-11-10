import Foundation
import Testing
import QRCore
import SevenKit
@testable import QRUI

@Suite struct SignalTextTests {
    static let argSets: [[String: String]] = [
        [:],
        ["brand": "PayPal", "scheme": "javascript", "host": "bank.example", "tld": "zip", "days": "12", "kind": "password"],
        ["brand": "Bank of America", "scheme": "myapp", "tld": ".top", "days": "1", "kind": "code"],
        ["brand": "Visa", "days": "0", "kind": "card"],
        ["kind": "unexpected", "days": "many", "brand": "", "scheme": "  "],
    ]

    static func words(_ text: String) -> Int {
        text.split(whereSeparator: { $0.isWhitespace }).count
    }

    static func containsBannedWord(_ text: String) -> Bool {
        text.range(of: "\\bsafe", options: [.regularExpression, .caseInsensitive]) != nil
    }

    @Test func thereAreThirtyFourSignalIDs() {
        #expect(SignalID.allCases.count == 34)
    }

    @Test func everySignalIDHasAShortPlainSentenceWithAndWithoutArgs() {
        for id in SignalID.allCases {
            for args in Self.argSets {
                let text = SignalText.sentence(for: Signal(id, args))
                #expect(!text.isEmpty, "\(id)")
                #expect(Self.words(text) <= 20, "\(id) (\(Self.words(text)) words): \(text)")
                #expect(!Self.containsBannedWord(text), "\(id): \(text)")
                #expect(!text.contains("{") && !text.contains("%@") && !text.contains("\\("), "\(id): \(text)")
                #expect(text.last == "." , "\(id): \(text)")
            }
        }
    }

    @Test func sentencesWithoutArgsAreDistinct() {
        let all = SignalID.allCases.map { SignalText.sentence(for: Signal($0)) }
        #expect(Set(all).count == all.count)
    }

    @Test func argsAreUsedWhenPresent() {
        #expect(SignalText.sentence(for: Signal(.lookalikeBrand, ["brand": "PayPal"])).contains("PayPal"))
        #expect(SignalText.sentence(for: Signal(.brandInDomain, ["brand": "Visa"])).contains("Visa"))
        #expect(SignalText.sentence(for: Signal(.riskyTLD, ["tld": "zip"])).contains(".zip"))
        #expect(SignalText.sentence(for: Signal(.riskyTLD, ["tld": ".zip"])).contains(".zip") )
        #expect(!SignalText.sentence(for: Signal(.riskyTLD, ["tld": ".zip"])).contains(".."))
        #expect(SignalText.sentence(for: Signal(.domainNew, ["days": "12"])).contains("12 days"))
        #expect(SignalText.sentence(for: Signal(.domainVeryNew, ["days": "1"])).contains("1 day "))
        #expect(SignalText.sentence(for: Signal(.unknownScheme, ["scheme": "myapp"])).contains("myapp"))
        #expect(SignalText.sentence(for: Signal(.asksForSecret, ["kind": "password"])).contains("password"))
        #expect(SignalText.sentence(for: Signal(.asksForSecret, ["kind": "card"])).contains("card"))
        #expect(SignalText.sentence(for: Signal(.asksForSecret, ["kind": "code"])).contains("code"))
    }

    @Test func hostileArgsAreCutAndCleaned() {
        let long = String(repeating: "A", count: 10_000)
        let hostile = "Pay\u{202E}Pal\nline\u{0000}"
        for id in SignalID.allCases {
            let text = SignalText.sentence(for: Signal(id, ["brand": long, "scheme": hostile, "host": hostile, "tld": long]))
            #expect(text.count < 400, "\(id)")
            #expect(!text.unicodeScalars.contains { $0 == "\u{202E}" || $0 == "\n" || $0 == "\u{0000}" }, "\(id)")
        }
    }

    @Test func sentencesCollapseDuplicatesAndKeepOrder() {
        let signals = [Signal(.plainHTTP), Signal(.shortener), Signal(.plainHTTP)]
        let out = SignalText.sentences(for: signals)
        #expect(out == [SignalText.sentence(for: signals[0]), SignalText.sentence(for: signals[1])])
    }
}

@Suite struct SignalTextVerdictTests {
    @Test func verdictWords() {
        #expect(VerdictText.title(for: .normal) == "Looks normal")
        #expect(VerdictText.title(for: .careful) == "Be careful")
        #expect(VerdictText.title(for: .likelyScam) == "Likely scam")
    }

    @Test func verdictSymbolsComeFromSevenKit() {
        #expect(VerdictText.symbolName(for: .normal) == "checkmark.circle")
        #expect(VerdictText.symbolName(for: .careful) == "exclamationmark.triangle")
        #expect(VerdictText.symbolName(for: .likelyScam) == "xmark.octagon")
    }

    @Test func typeNames() {
        let ids = ["link", "appLink", "payment", "crypto", "wifi", "contact", "event", "phone", "sms", "email", "geo", "text", "binary"]
        let names = ids.map(VerdictText.typeName(for:))
        #expect(Set(names).count == ids.count)
        #expect(VerdictText.typeName(for: "link") == "Web link")
        #expect(VerdictText.typeName(for: "payment") == "Payment code")
        #expect(VerdictText.typeName(for: "wifi") == "Wi-Fi network")
        #expect(VerdictText.typeName(for: "from-the-future") == "Other")
    }

    @Test func noVerdictOrTypeStringSaysTheBannedWord() {
        let strings = [Level.normal, .careful, .likelyScam].map(VerdictText.title(for:))
            + ["link", "appLink", "payment", "crypto", "wifi", "contact", "event", "phone", "sms", "email", "geo", "text", "binary", "x"]
                .map(VerdictText.typeName(for:))
        for text in strings { #expect(!SignalTextTests.containsBannedWord(text), "\(text)") }
    }

    @Test func accessibilitySentenceReadsTheVerdictInOneLine() {
        let signals = [Signal(.lookalikeBrand, ["brand": "PayPal"]), Signal(.plainHTTP), Signal(.assumedHTTPS)]
        let text = VerdictText.accessibilitySentence(level: .likelyScam, typeName: "Web link", signals: signals)
        #expect(text.hasPrefix("Likely scam. Web link. "))
        #expect(text.contains("PayPal"))
        #expect(!text.contains("secure web address"))          // info signals are left out
        #expect(!text.contains(".."))
        #expect(!SignalTextTests.containsBannedWord(text))
        #expect(VerdictText.accessibilitySentence(level: .normal, typeName: "Text", signals: []) == "Looks normal. Text.")
    }
}
