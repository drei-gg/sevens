import Foundation

/// Text helpers shared by `WebAddress` (PLAN §6.1 step 1, bounds, paths).
enum URLText {
    static let maxBytes = 8192

    /// Cap at 8 KB of UTF-8 (at a scalar boundary), trim C0 controls and space at both ends,
    /// remove tab, CR and LF anywhere.
    static func clean(_ text: String) -> String {
        var scalars: [Unicode.Scalar] = []
        var bytes = 0
        for s in text.unicodeScalars {
            bytes += String(s).utf8.count
            if bytes > maxBytes { break }
            scalars.append(s)
        }
        var start = 0, end = scalars.count
        while start < end, scalars[start].value <= 0x20 { start += 1 }
        while end > start, scalars[end - 1].value <= 0x20 { end -= 1 }
        var out = String.UnicodeScalarView()
        for s in scalars[start..<end] where s != "\t" && s != "\r" && s != "\n" { out.append(s) }
        return String(out)
    }

    static func isLabelScalar(_ s: Unicode.Scalar) -> Bool {
        if s == "-" { return true }
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .nonspacingMark, .spacingMark, .decimalNumber:
            return true
        default:
            return false
        }
    }

    /// Splits at the first `?` or `#`: (path, rest including that character, hasRest).
    /// With `backslashes`, `\` in the path becomes `/`.
    static func splitPath(_ s: String, backslashes: Bool = false) -> (String, String, Bool) {
        let cut = s.firstIndex { $0 == "?" || $0 == "#" } ?? s.endIndex
        var path = String(s[..<cut])
        if backslashes { path = path.replacingOccurrences(of: "\\", with: "/") }
        return (path, String(s[cut...]), cut != s.endIndex)
    }

    /// RFC 3986 dot-segment removal (iterative); `%2e` counts as a dot, as in browsers.
    static func removeDotSegments(_ path: String) -> String {
        let segments = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        var out: [String] = []
        for (i, seg) in segments.enumerated() {
            let lower = seg.lowercased()
            let isLast = i == segments.count - 1
            let isSingle = lower == "." || lower == "%2e"
            let isDouble = ["..", ".%2e", "%2e.", "%2e%2e"].contains(lower)
            if isDouble {
                if out.count > 1 { out.removeLast() }
                if isLast { out.append("") }
            } else if isSingle {
                if isLast { out.append("") }
            } else {
                out.append(seg)
            }
        }
        let joined = out.joined(separator: "/")
        return joined.hasPrefix("/") ? joined : "/" + joined
    }
}
