// WP 1.3 owns this file.

public enum Currency {
    /// ISO 4217 numeric ("608") → alpha ("PHP") using the bundled table; nil if unknown.
    public static func alpha(numeric: String, table: [String: String]) -> String? {
        fatalError("WP 1.3")
    }
}
