import Foundation
import Testing
import QRCore
@testable import QRUI

/// SPEC §9 and §14: the Make extension's picture tells VoiceOver what the code holds, in words
/// a person recognises, not the raw encoding.
@Suite struct ExtensionMadeCodeLabelTests {
    @Test func previewsAreWhatThePersonShared() {
        #expect(ExtensionMadeCode.preview(for: .link("https://example.org/menu")) == "https://example.org/menu")
        #expect(ExtensionMadeCode.preview(for: .text("Table 4")) == "Table 4")
        let contact = ContactInfo(name: nil, givenName: "Ana", familyName: "Reyes", phones: ["+63 917 555 0100"])
        #expect(ExtensionMadeCode.preview(for: .contact(contact)) == "Ana Reyes")
        #expect(ExtensionMadeCode.preview(for: .geo(GeoInfo(latitude: "14.5", longitude: "121.0"))) == "14.5, 121.0")
    }

    @Test func labelNamesTheCodeAndItsContent() {
        let label = MakeSymbology.qr.accessibilityLabel(for: ExtensionMadeCode.preview(for: .link("https://example.org/menu")))
        #expect(label == "QR code for https://example.org/menu")
    }
}
