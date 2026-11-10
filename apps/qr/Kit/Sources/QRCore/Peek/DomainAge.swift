import Foundation

// WP 1.6 owns this file.

public enum DomainAge {
    /// Whole days from `registeredAt` to `now`, never negative.
    public static func days(registeredAt: Date, now: Date) -> Int { fatalError("WP 1.6") }
}
