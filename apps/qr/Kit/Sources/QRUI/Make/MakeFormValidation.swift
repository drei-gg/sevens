import Foundation

/// Light shape checks. They catch typos; they don't try to be a standard.
enum MakeFormValidation {
    static func trimmed(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// One "@", something before it, a dotted domain without spaces or edge dots.
    static func isPlausibleEmail(_ raw: String) -> Bool {
        let s = trimmed(raw)
        guard !s.isEmpty, s.count <= 254, s.unicodeScalars.allSatisfy({ !$0.properties.isWhitespace }) else { return false }
        let parts = s.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty else { return false }
        let domain = parts[1]
        guard domain.contains("."), !domain.hasPrefix("."), !domain.hasSuffix("."), !domain.contains("..") else { return false }
        return true
    }

    /// Digits plus `+ ( ) - . * # ,` and spaces, 3 to 20 digits.
    static func isPlausiblePhone(_ raw: String) -> Bool {
        let s = trimmed(raw)
        guard !s.isEmpty else { return false }
        let allowed = Set("0123456789+()-.*#, ")
        guard s.allSatisfy({ allowed.contains($0) }) else { return false }
        let digits = s.filter { $0.isASCII && $0.isNumber }.count
        return (3...20).contains(digits)
    }

    /// Parses "12.5" or "12,5". nil when not a finite number.
    static func number(_ raw: String) -> Double? {
        let s = trimmed(raw).replacingOccurrences(of: ",", with: ".")
        guard !s.isEmpty, let v = Double(s), v.isFinite else { return nil }
        return v
    }

    /// The number as the geo URI wants it: trimmed, "." decimal separator.
    static func normalizedNumber(_ raw: String) -> String {
        trimmed(raw).replacingOccurrences(of: ",", with: ".")
    }
}
