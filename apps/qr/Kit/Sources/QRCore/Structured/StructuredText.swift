import Foundation

/// Text helpers shared by the structured-payload parsers and the encoder. Everything here works on
/// Unicode scalars (a `;` followed by a combining mark is one `Character`, but still a separator).
enum StructuredText {
    /// Analysis input cap: scanned codes are attacker-controlled.
    static let maxBytes = 8192

    static func capped(_ s: String) -> String {
        let u = s.utf8
        if u.count <= maxBytes { return s }
        return String(decoding: Array(u.prefix(maxBytes)), as: UTF8.self)
    }

    /// Capped, with leading whitespace, newlines and a BOM removed.
    static func prepared(_ s: String) -> String {
        let c = capped(s)
        return String(c.drop(while: { $0.isWhitespace || $0.isNewline || $0 == "\u{FEFF}" }))
    }

    static func trimmed(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    static func nonEmpty(_ s: String?) -> String? {
        guard let s else { return nil }
        let t = trimmed(s)
        return t.isEmpty ? nil : t
    }

    static func make(_ scalars: [Unicode.Scalar]) -> String {
        var v = String.UnicodeScalarView()
        v.append(contentsOf: scalars)
        return String(v)
    }

    private static func lower(_ u: Unicode.Scalar) -> Unicode.Scalar {
        (u.value >= 65 && u.value <= 90) ? (Unicode.Scalar(u.value + 32) ?? u) : u
    }

    /// The rest of `s` after an ASCII `prefix`, matched case-insensitively; nil when it doesn't start with it.
    static func stripPrefix(_ s: String, _ prefix: String) -> String? {
        let sc = s.unicodeScalars
        var idx = sc.startIndex
        for p in prefix.unicodeScalars {
            guard idx < sc.endIndex, lower(sc[idx]) == lower(p) else { return nil }
            idx = sc.index(after: idx)
        }
        return String(sc[idx...])
    }

    /// Splits on `sep`; a backslash protects the scalar after it (both stay in the pieces).
    static func splitUnescaped(_ s: String, _ sep: Unicode.Scalar) -> [String] {
        var out: [String] = []
        var cur = String.UnicodeScalarView()
        var it = s.unicodeScalars.makeIterator()
        while let c = it.next() {
            if c == "\\" {
                cur.append(c)
                if let n = it.next() { cur.append(n) }
            } else if c == sep {
                out.append(String(cur))
                cur = String.UnicodeScalarView()
            } else {
                cur.append(c)
            }
        }
        out.append(String(cur))
        return out
    }

    /// `\x` becomes `x` (Wi-Fi, MeCard, MATMSG escaping).
    static func unescapeBackslash(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        var it = s.unicodeScalars.makeIterator()
        while let c = it.next() {
            if c == "\\" {
                if let n = it.next() { out.append(n) }
            } else {
                out.append(c)
            }
        }
        return String(out)
    }

    /// vCard / iCalendar TEXT escaping: `\n` and `\N` are newlines, `\x` is `x`.
    static func unescapeText(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        var it = s.unicodeScalars.makeIterator()
        while let c = it.next() {
            if c == "\\" {
                if let n = it.next() {
                    out.append(n == "n" || n == "N" ? "\n" : n)
                }
            } else {
                out.append(c)
            }
        }
        return String(out)
    }

    /// Inverse of `unescapeText` for `, ; \` and newlines.
    static func escapeText(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        var prevCR = false
        for c in s.unicodeScalars {
            switch c {
            case "\\", ";", ",":
                out.append("\\"); out.append(c); prevCR = false
            case "\r":
                out.append("\\"); out.append("n"); prevCR = true
            case "\n":
                if !prevCR { out.append("\\"); out.append("n") }
                prevCR = false
            default:
                out.append(c); prevCR = false
            }
        }
        return String(out)
    }

    /// `\x` escaping for Wi-Fi style values.
    static func escapeBackslash(_ s: String, _ special: String) -> String {
        let set = Set(special.unicodeScalars)
        var out = String.UnicodeScalarView()
        for c in s.unicodeScalars {
            if c == "\\" || set.contains(c) { out.append("\\") }
            out.append(c)
        }
        return String(out)
    }

    /// `KEY:value;KEY:value;;` bodies (Wi-Fi, MeCard, MATMSG): keys uppercased, values still escaped.
    static func keyed(_ body: String) -> [(key: String, value: String)] {
        var out: [(key: String, value: String)] = []
        for piece in splitUnescaped(body, ";") {
            let sc = Array(piece.unicodeScalars)
            guard let colon = sc.firstIndex(of: ":") else { continue }
            let key = make(Array(sc[..<colon])).trimmingCharacters(in: .whitespaces).uppercased()
            if key.isEmpty { continue }
            out.append((key, make(Array(sc[(colon + 1)...]))))
        }
        return out
    }

    static func normalizeNewlines(_ s: String) -> String {
        s.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
    }

    // MARK: percent-encoding

    static func hexValue(_ b: UInt8) -> UInt8? {
        switch b {
        case 0x30...0x39: return b - 0x30
        case 0x41...0x46: return b - 0x41 + 10
        case 0x61...0x66: return b - 0x61 + 10
        default: return nil
        }
    }

    static func percentDecode(_ s: String) -> String {
        let u = Array(s.utf8)
        var bytes: [UInt8] = []
        bytes.reserveCapacity(u.count)
        var i = 0
        while i < u.count {
            if u[i] == 0x25, i + 2 < u.count, let h = hexValue(u[i + 1]), let l = hexValue(u[i + 2]) {
                bytes.append(h << 4 | l)
                i += 3
            } else {
                bytes.append(u[i])
                i += 1
            }
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// Percent-encodes UTF-8 bytes except letters, digits, `-._~` and the ASCII characters in `keep`.
    static func percentEncode(_ s: String, keep: String = "") -> String {
        let extra = Set(keep.utf8)
        let digits = Array("0123456789ABCDEF".utf8)
        var out: [UInt8] = []
        for b in s.utf8 {
            let plain = (b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A)
                || b == 0x2D || b == 0x2E || b == 0x5F || b == 0x7E || extra.contains(b)
            if plain {
                out.append(b)
            } else {
                out.append(0x25); out.append(digits[Int(b >> 4)]); out.append(digits[Int(b & 15)])
            }
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// `a=b&c=d` with keys lowercased and values percent-decoded.
    static func queryPairs(_ q: String) -> [(key: String, value: String)] {
        var out: [(key: String, value: String)] = []
        for part in q.split(separator: "&", omittingEmptySubsequences: true) {
            let p = String(part)
            if let eq = p.firstIndex(of: "=") {
                out.append((String(p[..<eq]).lowercased(), percentDecode(String(p[p.index(after: eq)...]))))
            } else {
                out.append((p.lowercased(), ""))
            }
        }
        return out
    }

    /// Splits at the first occurrence of `sep` (scalar-based).
    static func splitFirst(_ s: String, _ sep: Unicode.Scalar) -> (String, String?) {
        let sc = Array(s.unicodeScalars)
        guard let i = sc.firstIndex(of: sep) else { return (s, nil) }
        return (make(Array(sc[..<i])), make(Array(sc[(i + 1)...])))
    }

    // MARK: line folding

    /// Folds a content line at 75 octets (RFC 2425/5545); continuation lines begin with one space.
    static func fold(_ line: String) -> String {
        var out = ""
        var cur = String.UnicodeScalarView()
        var n = 0
        var limit = 75
        for sc in line.unicodeScalars {
            let w = UTF8.width(sc)
            if n + w > limit {
                out += String(cur) + "\r\n "
                cur = String.UnicodeScalarView()
                n = 0
                limit = 74
            }
            cur.append(sc)
            n += w
        }
        return out + String(cur)
    }
}
