import Foundation
import Testing
import QRCore
@testable import QRUI

/// The About rows: the date, the quiet age line, the button and its result lines.
@Suite struct ListsAboutTests {
    static let en = Locale(identifier: "en_GB")

    @Test func listsTitleShowsTheBuildDateInThePersonsLanguage() {
        let lists = ListsVersion(schema: 1, version: 2026111000, built: "2026-11-10")
        #expect(ListsAboutText.title(for: lists, locale: Self.en) == "Scam lists from 10 November 2026")
        #expect(ListsAboutText.title(for: lists, locale: Locale(identifier: "fr_FR")) == "Scam lists from 10 novembre 2026")
        #expect(ListsAboutText.title(for: ListsVersion(schema: 1, version: 0, built: ""), locale: Self.en) == "Scam lists")
    }

    @Test func listsAreOldAfterSixMonths() throws {
        let built = "2026-11-10"
        let day = { (y: Int, m: Int, d: Int) in
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
            return calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 12)) ?? .distantPast
        }
        #expect(!ListsAboutText.isOld(built: built, now: day(2026, 11, 11)))
        #expect(!ListsAboutText.isOld(built: built, now: day(2027, 5, 10)))
        #expect(ListsAboutText.isOld(built: built, now: day(2027, 5, 12)))
        #expect(ListsAboutText.isOld(built: "", now: day(2026, 11, 11)))
    }

    @Test func listsUpdatedLineNamesTheNewDate() {
        let result = ListsUpdateResult.updated(ListsVersion(schema: 1, version: 2026120100, built: "2026-12-01"))
        #expect(ListsAboutText.sentence(for: result, locale: Self.en) == "Updated to lists from 1 December 2026.")
    }

    @Test @MainActor func listsModelShowsNoButtonWithoutAnUpdaterAndNoResetUnlessDownloaded() {
        let bundled = ListsVersion(schema: 1, version: 100, built: "2026-11-10")
        let model = ListsAboutModel(updater: nil, current: { bundled }, bundled: bundled)
        #expect(!model.canCheck && !model.usingDownloaded)
        let downloaded = ListsVersion(schema: 1, version: 101, built: "2026-12-01")
        #expect(ListsAboutModel(updater: nil, current: { downloaded }, bundled: bundled).usingDownloaded)
    }

    @Test @MainActor func listsModelChecksThenResets() async {
        let rig = ListsTestRig()
        let base = "https://raw.githubusercontent.com/acme-ui1/kit/lists/"
        let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 101, built: "2026-12-01"))
        PeekStubProtocol.register(base + "lists.json", .ok(body: bytes))
        PeekStubProtocol.register(base + "lists.json.sig", .ok(body: rig.sign(bytes)))
        let box = VersionBox(ListsVersion(schema: 1, version: 100, built: "2026-11-10"))
        let bundled = box.value
        let updater = ListsUpdater(configuration: rig.configuration(owner: "acme-ui1", repo: "kit"), store: rig.store,
                                   protocolClasses: [PeekStubProtocol.self], installedVersion: { 100 },
                                   apply: { data in box.value = data == nil ? bundled : ListsVersion(schema: 1, version: 101, built: "2026-12-01") })
        let model = ListsAboutModel(updater: updater, current: { box.value }, bundled: bundled)
        #expect(model.canCheck && model.phase == .idle)
        await model.check()
        let date = ListsAboutText.dateText(built: "2026-12-01") ?? ""   // in this device's language
        #expect(model.phase == .finished("Updated to lists from \(date)."))
        #expect(model.lists.version == 101 && model.usingDownloaded)
        model.resetToBundled()
        #expect(model.lists == bundled && !model.usingDownloaded)
        #expect(model.phase == .finished("Using the lists that came with the app."))
        #expect(rig.store.read() == nil)
    }
}

private final class VersionBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: ListsVersion
    init(_ value: ListsVersion) { stored = value }
    var value: ListsVersion {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}
