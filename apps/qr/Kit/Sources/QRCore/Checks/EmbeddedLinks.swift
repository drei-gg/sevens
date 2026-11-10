import Foundation

/// PLAN §6.2 "Embedded links": `http://`, `https://` and `www.` runs up to whitespace or
/// `< > "`, trailing `. , ) ]` dropped, at most 10.
enum EmbeddedLinks {
    static let maxLinks = 10

    static func find(in text: String?) -> [String] {
        guard let text, !text.isEmpty else { return [] }
        let s = Array(text.unicodeScalars)
        var out: [String] = []
        var i = 0
        while i < s.count, out.count < maxLinks {
            guard let prefixLength = prefixLength(at: i, in: s) else { i += 1; continue }
            var j = i
            while j < s.count, !isTerminator(s[j]) { j += 1 }
            while j > i + prefixLength, ".,)]".unicodeScalars.contains(s[j - 1]) { j -= 1 }
            if j > i + prefixLength {
                var v = String.UnicodeScalarView()
                v.append(contentsOf: s[i..<j])
                let link = String(v)
                if !out.contains(link) { out.append(link) }
                i = j
            } else {
                i += prefixLength
            }
        }
        return out
    }

    /// Several fields at once, in order, deduplicated, capped at 10.
    static func merge(_ first: [String], _ more: [String]?...) -> [String] {
        var out: [String] = []
        for l in first + more.flatMap({ $0 ?? [] }) where !out.contains(l) { out.append(l) }
        return Array(out.prefix(maxLinks))
    }

    private static func isTerminator(_ c: Unicode.Scalar) -> Bool {
        c.properties.isWhitespace || c == "<" || c == ">" || c == "\"" || c.value < 0x20
    }

    private static func prefixLength(at i: Int, in s: [Unicode.Scalar]) -> Int? {
        if matches("https://", at: i, in: s) { return 8 }
        if matches("http://", at: i, in: s) { return 7 }
        if matches("www.", at: i, in: s) {
            if i > 0, s[i - 1].properties.isAlphabetic || s[i - 1].properties.numericType != nil || s[i - 1] == "." || s[i - 1] == "-" { return nil }
            return 4
        }
        return nil
    }

    private static func matches(_ p: StaticString, at i: Int, in s: [Unicode.Scalar]) -> Bool {
        let u = p.utf8Start
        let n = p.utf8CodeUnitCount
        guard i + n <= s.count else { return false }
        for k in 0..<n {
            let c = s[i + k].value
            let lower = (65...90).contains(c) ? c + 32 : c
            if lower != UInt32(u[k]) { return false }
        }
        return true
    }
}
