import Foundation
import Testing
import QRCore
import SevenKit
import SevenKitCore
@testable import QRUI

@MainActor
enum AppModelTestSupport {
    enum Facts {
        case fresh, trial, expired, unlocked, pending
        var value: EntitlementFacts {
            let now = Date()
            switch self {
            case .fresh: return EntitlementFacts()
            case .trial: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-86_400))
            case .expired: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-9 * 86_400))
            case .unlocked: return EntitlementFacts(hasUnlock: true)
            case .pending: return EntitlementFacts(purchasePending: true)
            }
        }
    }

    struct Rig {
        let model: AppModel
        let source: FixedSource
        let defaults: UserDefaults
    }

    /// A model with a started store on `FixedSource` facts, in-memory History, a stub analyzer.
    static func rig(_ facts: Facts, keepHistory: Bool = true, directory: URL? = nil) async -> Rig {
        let suite = "qr.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        defaults.removePersistentDomain(forName: suite)
        let source = FixedSource(facts: facts.value)
        let store = Store(config: SevenConfig(trialProductID: "t", unlockProductID: "u"), source: source,
                          trialStartStore: MemoryTrialStartStore(), defaults: defaults)
        await store.start()
        let settings = AppSettings(defaults: defaults)
        settings.keepHistory = keepHistory
        let model = AppModel(store: store, history: HistoryStore(directory: directory), settings: settings,
                             analyze: { code in
            Analysis(code: code, payload: .text(TextInfo(text: code.text ?? "")),
                     signals: [Signal(.plainHttp)], embedded: [], level: .careful)
        })
        return Rig(model: model, source: source, defaults: defaults)
    }

    static func analysis(_ text: String) -> Analysis {
        Analysis(code: ScannedCode(text: text), payload: .text(TextInfo(text: text)), signals: [], embedded: [], level: .normal)
    }
}

@MainActor
@Suite struct AppModelTests {
    typealias T = AppModelTestSupport

    @Test(arguments: [T.Facts.trial, .unlocked])
    func recordSavesWhenPaidFeaturesAreAvailable(_ facts: T.Facts) async {
        let rig = await T.rig(facts)
        let saved = await rig.model.record(T.analysis("https://example.org"), kind: .scanned)
        #expect(saved?.payload == "https://example.org")
        #expect(await rig.model.history.items.count == 1)
        await rig.model.refreshHistory()
        #expect(rig.model.historyItems.count == 1)
        #expect(rig.model.canSaveHistory)
        #expect(!rig.model.historyIsLocked)
    }

    @Test(arguments: [T.Facts.fresh, .expired, .pending])
    func recordSavesNothingWhenLocked(_ facts: T.Facts) async {
        let rig = await T.rig(facts)
        #expect(rig.model.historyIsLocked)
        #expect(await rig.model.record(T.analysis("https://example.org"), kind: .scanned) == nil)
        #expect(await rig.model.record(code: ScannedCode(text: "x"), kind: .made) == nil)
        #expect(await rig.model.history.items.isEmpty)
    }

    @Test func recordSavesNothingWhenKeepHistoryIsOff() async {
        let rig = await T.rig(.unlocked, keepHistory: false)
        #expect(!rig.model.canSaveHistory)
        #expect(await rig.model.record(T.analysis("a"), kind: .scanned) == nil)
        #expect(await rig.model.history.items.isEmpty)
        rig.model.settings.keepHistory = true
        #expect(await rig.model.record(T.analysis("a"), kind: .scanned) != nil)
    }

    @Test func recordCodeAnalyzesWithTheInjectedAnalyzer() async {
        let rig = await T.rig(.unlocked)
        let saved = await rig.model.record(code: ScannedCode(text: "made text"), kind: .made)
        #expect(saved?.kind == .made)
        #expect(saved?.level == .careful)
        #expect(saved?.signalIDs == ["plainHTTP"])
    }

    @Test func everythingButSavingStillWorksWhenLocked() async throws {
        let rig = await T.rig(.unlocked)
        let model = rig.model
        let first = await model.record(T.analysis("alpha one"), kind: .scanned)
        let second = await model.record(T.analysis("beta two"), kind: .made)
        #expect(first != nil && second != nil)

        // The trial ends (or the unlock is refunded).
        rig.source.facts = T.Facts.expired.value
        await model.store.refresh()
        #expect(model.historyIsLocked)

        #expect(await model.record(T.analysis("gamma"), kind: .scanned) == nil)    // nothing new
        #expect(await model.searchHistory("alpha").count == 1)                    // list + search
        #expect(await model.setNote("kept", for: try #require(first?.id)))        // notes on existing items
        #expect(await model.searchHistory("kept").count == 1)
        let json = try await model.exportHistoryJSON()                             // export
        guard case .items(let items) = HistoryFile.decode(json) else { Issue.record("export is not items"); return }
        #expect(items.count == 2)
        #expect(await model.exportHistoryCSV().contains("alpha one"))
        #expect(await model.deleteHistory(ids: [try #require(second?.id)]) == 1)  // delete
        await model.clearHistory()                                                // clear
        #expect(await model.history.items.isEmpty)
    }

    @Test func readOnlyHistoryIsReportedAndNothingIsSaved() async throws {
        let dir = HistoryTestSupport.tempDirectory()
        let bytes = Data("{\"schema\":42,\"items\":[]}".utf8)
        try bytes.write(to: HistoryTestSupport.fileURL(dir))
        let rig = await T.rig(.unlocked, directory: dir)
        await rig.model.refreshHistory()
        #expect(rig.model.historyIsReadOnly)
        #expect(rig.model.historyReadOnlyReason != nil)
        #expect(await rig.model.record(T.analysis("x"), kind: .scanned) == nil)
        await rig.model.clearHistory()
        await rig.model.history.flush()
        #expect(try Data(contentsOf: HistoryTestSupport.fileURL(dir)) == bytes)
    }

    @Test func startMirrorsHistoryChanges() async throws {
        let rig = await T.rig(.unlocked)
        await rig.model.start()
        await rig.model.start()          // twice is fine
        await rig.model.history.add(HistoryTestSupport.item("direct"))
        for _ in 0..<200 where rig.model.historyItems.isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        #expect(rig.model.historyItems.map(\.payload) == ["direct"])
        await rig.model.sceneDidEnterBackground()
    }

    @Test func analysisRunsTheInjectedAnalyzer() async {
        let rig = await T.rig(.unlocked)
        let result = await rig.model.analysis(of: ScannedCode(text: "hello"))
        #expect(result.level == .careful)
    }

    @Test func pendingCodeIsTakenOnce() async {
        let rig = await T.rig(.fresh)
        rig.model.pendingCode = ScannedCode(text: "injected")
        #expect(rig.model.takePendingCode()?.text == "injected")
        #expect(rig.model.pendingCode == nil)
        #expect(rig.model.takePendingCode() == nil)
    }

    @Test func keepHistoryIsPersistedAndDefaultsOn() async {
        let suite = "qr.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        #expect(AppSettings(defaults: defaults).keepHistory)
        AppSettings(defaults: defaults).keepHistory = false
        #expect(AppSettings(defaults: defaults).keepHistory == false)
        defaults.removePersistentDomain(forName: suite)
    }

    @Test func previewModelHasHistoryAndAState() async {
        let model = AppModel.preview(state: .expired)
        #expect(model.historyItems.count == AppModel.sampleHistory.count)
        #expect(await model.history.items.count == AppModel.sampleHistory.count)
    }
}

@MainActor
@Suite struct AppModelGateTests {
    @Test func unlockContentListsTheFeatures() {
        let content = QRGate.unlockContent(appName: "QR Code Kit")
        #expect(content.features.count == 4)
        #expect(content.appName == "QR Code Kit")
    }

    @Test func beforeYouBuyUsesThePriceOnlyThroughThePlaceholder() {
        let content = QRGate.beforeYouBuyContent()
        let template = content.paragraphs.map(\.text).joined(separator: " ")
        #expect(template.contains(BeforeYouBuyContent.pricePlaceholder))
        #expect(content.startTrialLabel == "Start my free 7 days")
        #expect(content.justScanLabel == "Just scan for free")
        #expect(content.resolvedParagraphs(price: "X9").joined().contains("X9"))
        let withoutPrice = content.resolvedParagraphs(price: nil).joined()
        #expect(!withoutPrice.contains("{price}"))
        #expect(!withoutPrice.contains("$"))
    }

    @Test func noGateTextSaysTheBannedWordOrNamesAPrice() {
        let content = QRGate.beforeYouBuyContent()
        let ended = QRGate.trialEndedContent()
        let all = (content.paragraphs.flatMap { [$0.text, $0.withoutPrice ?? ""] } + [content.title]
            + QRGate.unlockContent().features + ended.stillFree + ended.unlockKeeps)
        for text in all {
            #expect(text.range(of: "\\bsafe", options: [.regularExpression, .caseInsensitive]) == nil, "\(text)")
            #expect(text.range(of: "\\$\\s?\\d|seven dollars", options: [.regularExpression, .caseInsensitive]) == nil, "\(text)")
        }
        #expect(!ended.stillFree.isEmpty && !ended.unlockKeeps.isEmpty)
    }

    @Test func appNameFallsBackWhenTheBundleHasNone() {
        #expect(!QRGate.appName(bundle: Bundle(for: BundleToken.self)).isEmpty)
    }
}

private final class BundleToken {}
