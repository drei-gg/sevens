import Foundation

/// "Shown once" flags (Before you buy, trial ended), kept in `UserDefaults`.
public enum SevenFlags {
    private static let beforeYouBuyKey = "seven.flag.beforeYouBuyShown"
    private static let trialEndedKey = "seven.flag.trialEndedShown"

    public static func beforeYouBuyShown(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: beforeYouBuyKey)
    }
    public static func markBeforeYouBuyShown(_ defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: beforeYouBuyKey)
    }
    public static func trialEndedShown(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: trialEndedKey)
    }
    #if DEBUG
    /// Debug launch argument support: both sheets count as never shown.
    public static func resetShownFlags(_ defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: beforeYouBuyKey)
        defaults.removeObject(forKey: trialEndedKey)
    }
    #endif
    public static func markTrialEndedShown(_ defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: trialEndedKey)
    }
}
