import Foundation

/// When the free trial begins (PLAN D1, C12).
public enum TrialPolicy: Sendable, Equatable {
    /// The person taps "Start my free 7 days" (a $0 purchase). The default.
    case onPurchase
    /// The first launch starts it; the start date lives in the Keychain. The first-launch
    /// buttons collapse to "Continue".
    case onFirstLaunch
}

/// Pure helpers for showing trial progress. None of these decide access; `entitlementState` does.
public enum TrialMath {
    public static let secondsPerDay: TimeInterval = 86_400

    /// 1-based day of the trial, clamped to `1...totalDays`. A clock before the start gives 1.
    public static func dayNumber(startedAt: Date, now: Date, trialLength: TimeInterval = 7 * secondsPerDay) -> Int {
        let total = totalDays(trialLength)
        let elapsed = max(0, now.timeIntervalSince(startedAt))
        let day = Int(elapsed / secondsPerDay) + 1
        return min(max(day, 1), total)
    }

    /// Whole days left, rounded up, never negative. Exactly at the end it is 0.
    public static func daysRemaining(endsAt: Date, now: Date) -> Int {
        let left = endsAt.timeIntervalSince(now)
        guard left > 0 else { return 0 }
        return Int((left / secondsPerDay).rounded(.up))
    }

    public static func totalDays(_ trialLength: TimeInterval) -> Int {
        max(1, Int((trialLength / secondsPerDay).rounded(.up)))
    }
}
