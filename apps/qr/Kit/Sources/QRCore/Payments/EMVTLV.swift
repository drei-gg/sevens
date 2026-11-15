// WP 1.3: the EMVCo TLV reader. Two-digit tag, two-digit length, value.
// Works on 16-bit units so the same code counts lengths as UTF-8 bytes or UTF-16 code units.

struct EMVTLVItem {
    var tag: String
    var value: [UInt16]
}

struct EMVTLVResult {
    var items: [EMVTLVItem]
    /// True when every unit was consumed by well-formed TLVs.
    var complete: Bool
}

enum EMVTLV {
    enum Unit {
        case utf8, utf16

        func text(_ units: [UInt16]) -> String {
            switch self {
            case .utf8: return String(decoding: units.map { UInt8(truncatingIfNeeded: $0) }, as: UTF8.self)
            case .utf16: return String(decoding: units, as: UTF16.self)
            }
        }
    }

    static func units(of text: String, _ unit: Unit) -> [UInt16] {
        switch unit {
        case .utf8: return text.utf8.map { UInt16($0) }
        case .utf16: return Array(text.utf16)
        }
    }

    private static func digit(_ u: UInt16) -> Int? {
        (u >= 0x30 && u <= 0x39) ? Int(u - 0x30) : nil
    }

    /// Reads TLVs until the end or the first malformed one (which is dropped).
    static func read(_ units: [UInt16]) -> EMVTLVResult {
        var items: [EMVTLVItem] = []
        var i = 0
        let n = units.count
        while i < n {
            guard i + 4 <= n,
                  let t0 = digit(units[i]), let t1 = digit(units[i + 1]),
                  let l0 = digit(units[i + 2]), let l1 = digit(units[i + 3]) else {
                return EMVTLVResult(items: items, complete: false)
            }
            let length = l0 * 10 + l1
            let start = i + 4
            guard start + length <= n else { return EMVTLVResult(items: items, complete: false) }
            items.append(EMVTLVItem(tag: "\(t0)\(t1)", value: Array(units[start..<(start + length)])))
            i = start + length
        }
        return EMVTLVResult(items: items, complete: true)
    }
}
