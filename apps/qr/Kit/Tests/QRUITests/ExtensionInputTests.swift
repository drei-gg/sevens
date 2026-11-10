import Foundation
import QRCore
import Testing
@testable import QRUI

@Suite struct ExtensionInputTests {
    @Test func webURLBecomesLink() {
        #expect(ExtensionMakeInput.decide(.webURL(" https://maps.apple.com/?q=cafe ")) == .request(.link("https://maps.apple.com/?q=cafe")))
    }

    @Test func plainTextBecomesText() {
        #expect(ExtensionMakeInput.decide(.text("hello\n")) == .request(.text("hello")))
    }

    @Test func blankTextIsUnsupported() {
        #expect(ExtensionMakeInput.decide(.text("  \n")) == .unsupported)
        #expect(ExtensionMakeInput.decide(.webURL("")) == .unsupported)
    }

    @Test func vCardBecomesContact() {
        let card = "BEGIN:VCARD\nVERSION:3.0\nFN:Ada Lovelace\nTEL:+441234567890\nEND:VCARD\n"
        guard case .request(.contact(let info)) = ExtensionMakeInput.decide(.vCard(card)) else {
            Issue.record("expected a contact")
            return
        }
        #expect(info.name == "Ada Lovelace")
    }

    @Test func unparsableVCardFallsBackToText() {
        let decision = ExtensionMakeInput.decide(.vCard("not really a card"))
        #expect(decision == .request(.text("not really a card")))
    }

    @Test func otherFilesAndNothingAreUnsupported() {
        #expect(ExtensionMakeInput.decide(.otherFile) == .unsupported)
        #expect(ExtensionMakeInput.decide(nil) == .unsupported)
    }
}

@Suite struct ExtensionSummaryTests {
    private func value(_ text: String) -> String? {
        let analysis = Analyzer(data: BundledData.shared).analyze(ScannedCode(text: text))
        return ExtensionSummary.main(for: analysis.payload)?.value
    }

    @Test func linkShowsDomain() {
        #expect(value("https://example.com/a/b?c=1") == "example.com")
    }

    @Test func wifiShowsSSID() {
        #expect(value("WIFI:T:WPA;S:Cafe Guest;P:pass1234;;") == "Cafe Guest")
    }

    @Test func phoneShowsNumber() {
        #expect(value("tel:+441234567890") == "+441234567890")
    }

    @Test func longTextIsTruncated() {
        let long = String(repeating: "a", count: 500)
        let shown = value(long)
        #expect((shown?.count ?? 0) <= ExtensionSummary.maxValueLength + 1)
    }

    @Test func binaryShowsSize() {
        #expect(ExtensionSummary.main(for: .binary(byteCount: 12))?.value.contains("12") == true)
    }

    @Test func emptyFieldGivesNoSummary() {
        #expect(ExtensionSummary.main(for: .phone(PhoneInfo(number: " "))) == nil)
    }
}
