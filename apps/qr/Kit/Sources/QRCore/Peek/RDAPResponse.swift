import Foundation

// WP 1.6 owns this file.

public enum RDAPResponse {
    /// The `events[]` entry whose `eventAction` is `registration`, or nil.
    public static func registrationDate(json: Data) -> Date? { fatalError("WP 1.6") }
}
