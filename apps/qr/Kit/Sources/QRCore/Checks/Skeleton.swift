import Foundation

/// Confusable skeleton (PLAN §6.2 `lookalikeBrand`): lowercase, map through `confusables.json`
/// (longest key first), then strip hyphens.
struct Skeleton: Sendable {
    /// Maps grouped by key length in scalars, longest first.
    private let tables: [(length: Int, map: [String: String])]

    init(confusables: [String: String]) {
        var byLength: [Int: [String: String]] = [:]
        for (k, v) in confusables {
            let n = k.unicodeScalars.count
            guard n > 0, n <= 8 else { continue }
            byLength[n, default: [:]][k] = v
        }
        tables = byLength.keys.sorted(by: >).map { ($0, byLength[$0] ?? [:]) }
    }

    func of(_ label: String) -> String {
        let scalars = Array(label.lowercased().unicodeScalars)
        var out = String.UnicodeScalarView()
        var i = 0
        while i < scalars.count {
            var matched = false
            for (length, map) in tables where i + length <= scalars.count {
                var key = String.UnicodeScalarView()
                key.append(contentsOf: scalars[i..<(i + length)])
                if let v = map[String(key)] {
                    out.append(contentsOf: v.unicodeScalars)
                    i += length
                    matched = true
                    break
                }
            }
            if !matched { out.append(scalars[i]); i += 1 }
        }
        return String(out).filter { $0 != "-" && $0 != "\u{2010}" && $0 != "\u{2011}" }
    }
}
