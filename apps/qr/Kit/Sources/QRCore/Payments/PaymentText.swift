import Foundation

/// Small text helpers shared by the non-EMV payment parsers (WP 1.4).
enum PaymentText {
    /// Payment payloads longer than this are not analysed (8 KB cap).
    static let maxBytes = 8192

    static func withinCap(_ text: String) -> Bool { text.utf8.count <= maxBytes }

    /// Percent-decodes UTF-8. A malformed escape stays literal. `+` is left alone.
    static func percentDecode(_ s: String) -> String {
        let bytes = Array(s.utf8)
        guard bytes.contains(UInt8(ascii: "%")) else { return s }
        var out: [UInt8] = []
        out.reserveCapacity(bytes.count)
        var i = 0
        while i < bytes.count {
            if bytes[i] == UInt8(ascii: "%"), i + 2 < bytes.count,
               let h = hexValue(bytes[i + 1]), let l = hexValue(bytes[i + 2]) {
                out.append(UInt8(h * 16 + l))
                i += 3
            } else {
                out.append(bytes[i])
                i += 1
            }
        }
        return String(decoding: out, as: UTF8.self)
    }

    private static func hexValue(_ b: UInt8) -> Int? {
        switch b {
        case 48...57: return Int(b) - 48
        case 65...70: return Int(b) - 55
        case 97...102: return Int(b) - 87
        default: return nil
        }
    }

    /// Query string → lowercase key to decoded value; the first occurrence of a key wins.
    static func queryItems(_ query: Substring) -> [String: String] {
        var out: [String: String] = [:]
        for part in query.split(separator: "&", omittingEmptySubsequences: true) {
            let key: Substring, value: Substring
            if let eq = part.firstIndex(of: "=") {
                key = part[part.startIndex..<eq]
                value = part[part.index(after: eq)...]
            } else {
                key = part
                value = ""
            }
            let k = percentDecode(String(key)).lowercased()
            if out[k] == nil { out[k] = percentDecode(String(value)) }
        }
        return out
    }

    /// Lines split on LF with an optional CR before it. Empty lines are kept (position matters).
    static func lines(_ text: String) -> [String] {
        var out: [String] = []
        var current = ""
        for ch in text.unicodeScalars {
            if ch == "\n" {
                if current.hasSuffix("\r") { current.removeLast() }
                out.append(current)
                current = ""
            } else {
                current.unicodeScalars.append(ch)
            }
        }
        if current.hasSuffix("\r") { current.removeLast() }
        out.append(current)
        return out
    }

    /// Trimmed, nil when empty.
    static func nonEmpty(_ s: String?) -> String? {
        guard let t = s?.trimmingCharacters(in: .whitespaces), !t.isEmpty else { return nil }
        return t
    }
}
