import Foundation

/// Host handling for `WebAddress`. Pure functions; no Foundation.URL.
enum URLHost {
    struct Parsed {
        var ascii: String
        var unicode: String
        var kind: WebAddress.HostKind
        var malformed: Bool
    }

    static func percentDecode(_ s: String) -> String? {
        let bytes = Array(s.utf8)
        var out: [UInt8] = []
        out.reserveCapacity(bytes.count)
        var i = 0
        func hex(_ b: UInt8) -> UInt8? {
            switch b {
            case 48...57: return b - 48
            case 65...70: return b - 55
            case 97...102: return b - 87
            default: return nil
            }
        }
        while i < bytes.count {
            if bytes[i] == UInt8(ascii: "%"), i + 2 < bytes.count, let h = hex(bytes[i + 1]), let l = hex(bytes[i + 2]) {
                out.append(h << 4 | l)
                i += 3
            } else {
                out.append(bytes[i])
                i += 1
            }
        }
        return String(bytes: out, encoding: .utf8)
    }

    private static let forbidden: Set<Unicode.Scalar> = [" ", "#", "/", ":", "<", ">", "?", "@", "[", "\\", "]", "^", "|", "%"]

    /// `raw` is the host as written, between userinfo and port (brackets included for IPv6).
    static func parse(_ raw: String) -> Parsed? {
        if raw.hasPrefix("[") {
            guard raw.hasSuffix("]"), raw.count >= 4 else { return nil }
            let inner = String(raw.dropFirst().dropLast()).lowercased()
            let okChars = inner.unicodeScalars.allSatisfy { $0.isASCII && (("0"..."9").contains($0) || ("a"..."f").contains($0) || $0 == ":" || $0 == ".") }
            guard okChars, inner.contains(":"), inner.count <= 45, inner.components(separatedBy: "::").count <= 2 else { return nil }
            let h = "[" + inner + "]"
            return Parsed(ascii: h, unicode: h, kind: .ipv6, malformed: false)
        }
        guard var host = percentDecode(raw) else { return nil }
        // Browsers fold compatibility forms (full-width letters, ligatures) before looking the host
        // up, so we check the folded form (SPEC §4.2). Folding can create characters a host can't
        // hold (full-width `/`, `#`, `@`), so the forbidden-character test runs afterwards.
        host = host.precomposedStringWithCompatibilityMapping
        host = host.unicodeScalars.map { s -> String in
            (s.value == 0x3002 || s.value == 0xFF0E || s.value == 0xFF61) ? "." : String(s)
        }.joined()
        for scalar in host.unicodeScalars where forbidden.contains(scalar) || scalar.value <= 0x20 || scalar.value == 0x7F {
            return nil
        }
        host = host.lowercased().precomposedStringWithCompatibilityMapping
        if host.hasSuffix(".") { host.removeLast() }
        guard !host.isEmpty else { return nil }

        let labels = host.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        if let last = labels.last, isNumericLabel(last) {
            guard let v4 = parseIPv4(labels) else { return nil }
            return Parsed(ascii: v4, unicode: v4, kind: .ipv4, malformed: false)
        }
        var ascii: [String] = [], unicode: [String] = []
        var malformed = false
        for label in labels {
            if label.unicodeScalars.allSatisfy({ $0.isASCII }) {
                ascii.append(label)
                if label.hasPrefix("xn--") {
                    if let d = Punycode.decode(String(label.dropFirst(4))), d.unicodeScalars.contains(where: { !$0.isASCII }) {
                        unicode.append(d)
                    } else {
                        unicode.append(label); malformed = true
                    }
                } else {
                    unicode.append(label)
                }
            } else if let e = Punycode.encode(label) {
                ascii.append("xn--" + e); unicode.append(label)
            } else {
                ascii.append(label); unicode.append(label); malformed = true
            }
        }
        return Parsed(ascii: ascii.joined(separator: "."), unicode: unicode.joined(separator: "."), kind: .domain, malformed: malformed)
    }

    /// Unicode form of an ASCII host or part of one; labels that don't decode stay as they are.
    static func unicodeForm(_ ascii: String) -> String {
        ascii.split(separator: ".", omittingEmptySubsequences: false).map { label -> String in
            if label.hasPrefix("xn--"), let d = Punycode.decode(String(label.dropFirst(4))) { return d }
            return String(label)
        }.joined(separator: ".")
    }

    private static func isNumericLabel(_ s: String) -> Bool {
        guard !s.isEmpty else { return false }
        if s.allSatisfy({ $0.isASCII && $0.isNumber }) { return true }
        let l = s.lowercased()
        return l.hasPrefix("0x") && l.dropFirst(2).allSatisfy { $0.isASCII && $0.isHexDigit }
    }

    private static func parseIPv4Part(_ s: String) -> UInt64? {
        guard !s.isEmpty, s.count <= 12 else { return nil }
        var digits = Substring(s.lowercased())
        var radix = 10
        if digits.hasPrefix("0x") { digits = digits.dropFirst(2); radix = 16 }
        else if digits.count > 1, digits.hasPrefix("0") { digits = digits.dropFirst(); radix = 8 }
        if digits.isEmpty { return 0 }
        guard digits.allSatisfy({ $0.isASCII }) else { return nil }
        return UInt64(digits, radix: radix)
    }

    private static func parseIPv4(_ parts: [String]) -> String? {
        guard (1...4).contains(parts.count) else { return nil }
        var nums: [UInt64] = []
        for p in parts { guard let v = parseIPv4Part(p) else { return nil }; nums.append(v) }
        for v in nums.dropLast() where v > 255 { return nil }
        let lastLimit: UInt64 = 1 << (8 * UInt64(5 - nums.count))
        guard nums[nums.count - 1] < lastLimit else { return nil }
        var ip = nums[nums.count - 1]
        for (i, v) in nums.dropLast().enumerated() { ip += v << (8 * UInt64(3 - i)) }
        return "\((ip >> 24) & 255).\((ip >> 16) & 255).\((ip >> 8) & 255).\(ip & 255)"
    }
}
