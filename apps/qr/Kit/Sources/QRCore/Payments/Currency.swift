// WP 1.3 owns this file.

public enum Currency {
    /// ISO 4217 numeric ("608") → alpha ("PHP") using the bundled table; nil if unknown.
    public static func alpha(numeric: String, table: [String: String]) -> String? {
        let digits = numeric.utf8
        guard !digits.isEmpty, digits.count <= 3, digits.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
        let padded = String(repeating: "0", count: 3 - digits.count) + numeric
        guard let alpha = table[padded], !alpha.isEmpty else { return nil }
        return alpha
    }
}
