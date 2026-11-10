import Foundation
import QRCore
import SevenKit
import SevenKitCore

extension AppModel {
    /// Trial states for previews and tests.
    public enum PreviewState: Sendable, CaseIterable {
        case fresh, trial, expired, unlocked, pending

        var facts: EntitlementFacts {
            let now = Date()
            switch self {
            case .fresh: return EntitlementFacts()
            case .trial: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-2 * 86_400))
            case .expired: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-9 * 86_400))
            case .unlocked: return EntitlementFacts(hasUnlock: true)
            case .pending: return EntitlementFacts(purchasePending: true)
            }
        }
    }

    /// For SwiftUI previews (light, dark, large text): SevenKit's `FixedSource`, an in-memory
    /// History seeded with `history`, and a stand-in analyzer that never touches `BundledData`
    /// (it returns a plain-text analysis).
    ///
    ///     .qrStore(.preview(state: .trial))
    public static func preview(state: PreviewState = .unlocked, history items: [HistoryItem] = AppModel.sampleHistory) -> AppModel {
        let config = SevenConfig(trialProductID: "preview.trial", unlockProductID: "preview.unlock")
        let source = FixedSource(facts: state.facts)
        let store = Store(config: config, source: source, trialStartStore: MemoryTrialStartStore(),
                          defaults: UserDefaults(suiteName: "qr.preview") ?? .standard)
        let model = AppModel(
            store: store,
            history: HistoryStore(directory: nil, seed: items),
            settings: AppSettings(defaults: UserDefaults(suiteName: "qr.preview") ?? .standard),
            analyze: previewAnalyze
        )
        model.seedHistoryMirror(items.sorted { first, second in first.date > second.date })
        Task { await store.start() }
        return model
    }

    static let previewAnalyze: Analyze = { code in
        let text = code.text ?? ""
        return Analysis(code: code, payload: .text(TextInfo(text: text)), signals: [], embedded: [], level: .normal)
    }

    public static var sampleHistory: [HistoryItem] {
        let now = Date()
        return [
            HistoryItem(kind: .scanned, payload: "https://example.org/menu", symbology: .qr, typeID: "link",
                        level: .normal, signalIDs: [], date: now.addingTimeInterval(-600), note: "Lunch place"),
            HistoryItem(kind: .scanned, payload: "http://paypa1-verify.example/login", symbology: .qr, typeID: "link",
                        level: .likelyScam, signalIDs: ["lookalikeBrand", "plainHTTP"], date: now.addingTimeInterval(-86_400)),
            HistoryItem(kind: .made, payload: "WIFI:T:WPA;S:Home;P:hunter2;;", symbology: .qr, typeID: "wifi",
                        level: .normal, signalIDs: [], date: now.addingTimeInterval(-3 * 86_400)),
        ]
    }
}
