import Foundation

public enum DomainAge {
    /// Whole days from `registeredAt` to `now` (floor), never negative.
    public static func days(registeredAt: Date, now: Date) -> Int {
        let seconds = now.timeIntervalSince(registeredAt)
        guard seconds.isFinite, seconds >= 86_400 else { return 0 }
        let whole = (seconds / 86_400).rounded(.down)
        // 10^9 days is far beyond any registration; clamp so Int() can never trap.
        return Int(min(whole, 1_000_000_000))
    }
}
