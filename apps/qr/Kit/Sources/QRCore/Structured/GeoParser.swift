import Foundation

/// geo:lat,lon[,alt][;params][?q=…]
public enum GeoParser {
    public static func parse(_ text: String) -> GeoInfo? {
        guard let rest = StructuredText.stripPrefix(StructuredText.prepared(text), "geo:") else { return nil }
        let (path, query) = StructuredText.splitFirst(StructuredText.trimmed(rest), "?")
        let coords = StructuredText.splitFirst(path, ";").0
            .split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
        guard coords.count == 2 || coords.count == 3, coords.allSatisfy(isDecimal),
              let lat = Double(coords[0]), let lon = Double(coords[1]),
              abs(lat) <= 90, abs(lon) <= 180 else { return nil }
        let q = query.flatMap { StructuredText.queryPairs($0).first { $0.key == "q" && !$0.value.isEmpty }?.value }
        return GeoInfo(latitude: coords[0], longitude: coords[1],
                       altitude: coords.count == 3 ? coords[2] : nil, query: q)
    }

    /// `-12.5`, `+3`, `0.25`: sign, digits, optional fraction. No exponent, no NaN.
    private static func isDecimal(_ s: String) -> Bool {
        var digits = 0
        var dot = false
        for (i, c) in s.unicodeScalars.enumerated() {
            if c.value >= 48 && c.value <= 57 { digits += 1 }
            else if c == "." && !dot { dot = true }
            else if (c == "-" || c == "+") && i == 0 { continue }
            else { return false }
        }
        return digits > 0
    }
}
