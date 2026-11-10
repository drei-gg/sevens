import Foundation
import Testing
@testable import QRUI

@Suite struct SettingsInfoTests {
    @Test func noticesNameEverythingThePlanRequires() {
        let text = SettingsNotices.paragraphs().joined(separator: "\n")
        #expect(text.contains("Public Suffix List"))
        #expect(text.contains("MPL-2.0"))
        #expect(text.contains("https://publicsuffix.org"))
        #expect(text.contains("Unicode License v3"))
        #expect(text.contains("QR Code is a registered trademark of DENSO WAVE INCORPORATED."))
    }

    @Test func networkContactListsTheFiveRowsFromTheSpec() {
        let entries = NetworkContactContent.entries()
        #expect(entries.count == 5)
        #expect(entries.map(\.when) == ["You tap Peek", "You tap Peek", "You tap Open", "You buy or restore", "You tap Check for newer lists"])
        #expect(entries[0].sent == "A normal page request with no cookies")
        #expect(entries[1].sent == "The domain name")
        #expect(entries[3].who == "Apple, through StoreKit")
        #expect(entries[4].who == "GitHub, where our public code lives")
        #expect(entries[4].sent == "A request for one file. Nothing about you or what you scanned.")
        #expect(NetworkContactContent.closing == "Anything not listed here doesn't happen.")
        #expect(NetworkContactContent.privacyLabel == "App Store privacy label: Data Not Collected.")
    }

    @Test func missingBundleValuesHideTheirRows() {
        let bundle = Bundle(for: SettingsInfoProbe.self)
        // The test bundle has none of the Sevens keys or a CHANGELOG.
        #expect(SettingsInfo.supportEmail(bundle: bundle) == nil)
        #expect(SettingsInfo.repositoryURL(bundle: bundle) == nil)
        #expect(SettingsInfo.changelog(bundle: bundle) == nil)
    }
}

private final class SettingsInfoProbe {}
