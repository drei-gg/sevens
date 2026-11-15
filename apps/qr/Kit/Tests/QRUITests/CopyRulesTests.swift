import Foundation
import Testing
import QRCore
import SevenKit
@testable import QRUI

/// WP 4.3 copy rules: plain verdict wording, no reassurance word, and no pressure or hard-coded
/// price in the unlock flow (SPEC §13, G7).
@Suite struct CopyRulesTests {
    static let argSets: [[String: String]] = [
        [:],
        ["brand": "PayPal", "scheme": "javascript", "host": "bank.example", "tld": "zip", "days": "12", "kind": "password"],
        ["brand": "Bank of America", "scheme": "myapp", "tld": ".top", "days": "1", "kind": "code"],
        ["brand": "Visa", "days": "0", "kind": "card"],
    ]

    static let pressure = ["hurry", "only today", "limited", "don’t miss", "don't miss", "last chance"]

    static func wordCount(_ text: String) -> Int { text.split(whereSeparator: { $0.isWhitespace }).count }
    static func hasSafe(_ text: String) -> Bool {
        text.range(of: "\\bsafe", options: [.regularExpression, .caseInsensitive]) != nil
    }
    static func hasPrice(_ text: String) -> Bool {
        text.range(of: "\\$\\s?\\d", options: .regularExpression) != nil
    }
    static func hasPressure(_ text: String) -> Bool {
        let lower = text.lowercased().replacingOccurrences(of: "\u{2019}", with: "'")
        return pressure.contains { lower.contains($0) }
    }

    static var gateTexts: [String] {
        let unlock = QRGate.unlockContent(appName: "QR Code Kit")
        let buy = QRGate.beforeYouBuyContent()
        let ended = QRGate.trialEndedContent()
        var all = unlock.features + [QRGate.appName(bundle: Bundle(for: BundleToken.self))]
        all += [buy.title, buy.startTrialLabel, buy.justScanLabel, buy.continueLabel].compactMap { $0 }
        for paragraph in buy.paragraphs { all += [paragraph.text, paragraph.withoutPrice].compactMap { $0 } }
        all += ended.stillFree + ended.unlockKeeps
        return all
    }

    private final class BundleToken {}

    @Test func copyEverySignalSentenceIsShortAndNeverSafe() {
        for id in SignalID.allCases {
            for args in Self.argSets {
                let text = SignalText.sentence(for: Signal(id, args))
                #expect(Self.wordCount(text) <= 20, "\(id) (\(Self.wordCount(text)) words): \(text)")
                #expect(!Self.hasSafe(text), "\(id): \(text)")
            }
        }
    }

    @Test func copyVerdictTitlesAreShortAndNeverSafe() {
        for level in [Level.normal, .careful, .likelyScam] {
            let title = VerdictText.title(for: level)
            #expect(!title.isEmpty)
            #expect(Self.wordCount(title) <= 20, "\(title)")
            #expect(!Self.hasSafe(title), "\(title)")
            #expect(!Self.hasPrice(title), "\(title)")
            #expect(!Self.hasPressure(title), "\(title)")
        }
    }

    @Test func copyGateTextsHaveNoSafeNoPriceNoPressure() {
        let texts = Self.gateTexts
        #expect(texts.count > 15)
        for text in texts {
            #expect(!Self.hasSafe(text), "\(text)")
            #expect(!Self.hasPrice(text), "\(text)")
            #expect(!Self.hasPressure(text), "\(text)")
        }
    }
}
