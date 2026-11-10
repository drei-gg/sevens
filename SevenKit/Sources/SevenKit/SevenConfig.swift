import Foundation
import SevenKitCore

/// Product IDs, app group and trial length of one Seven app. The app reads them from its
/// Info.plist (`SevensProductTrial`, `SevensProductUnlock`, `SevensAppGroup`); tests build one
/// with the explicit init.
public struct SevenConfig: Sendable, Equatable {
    public var productIDs: SevenProductIDs
    /// Suite name of the app group shared with extensions. Nil when the app has none.
    public var appGroup: String?
    public var trialLength: TimeInterval

    public init(
        trialProductID: String,
        unlockProductID: String,
        appGroup: String? = nil,
        trialLength: TimeInterval = 7 * 86_400
    ) {
        self.productIDs = SevenProductIDs(trial: trialProductID, unlock: unlockProductID)
        self.appGroup = appGroup
        self.trialLength = trialLength
    }

    /// Reads the three Info.plist keys. Returns nil when a product key is missing, empty or an
    /// unexpanded build setting like `$(SEVENS_PRODUCT_ID_PREFIX).trial7`.
    public init?(bundle: Bundle = .main) {
        func value(_ key: String) -> String? {
            guard let raw = bundle.object(forInfoDictionaryKey: key) as? String else { return nil }
            let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty || text.contains("$(") ? nil : text
        }
        guard let trial = value("SevensProductTrial"), let unlock = value("SevensProductUnlock") else { return nil }
        self.init(trialProductID: trial, unlockProductID: unlock, appGroup: value("SevensAppGroup"))
    }
}
