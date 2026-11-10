import Foundation

/// Script table for `mixedScript` (PLAN §6.2). Small and in code on purpose.
enum ScriptMix {
    enum Script: String, Sendable, Hashable, CaseIterable {
        case latin = "Latin", greek = "Greek", cyrillic = "Cyrillic", armenian = "Armenian", hebrew = "Hebrew",
             arabic = "Arabic", devanagari = "Devanagari", bengali = "Bengali", tamil = "Tamil", thai = "Thai",
             georgian = "Georgian", hangul = "Hangul", hiragana = "Hiragana", katakana = "Katakana",
             han = "Han", bopomofo = "Bopomofo", other = "Other"
    }

    private static let ranges: [(Script, [ClosedRange<UInt32>])] = [
        (.latin, [0x41...0x5A, 0x61...0x7A, 0xAA...0xAA, 0xBA...0xBA, 0xC0...0xD6, 0xD8...0xF6, 0xF8...0x2AF,
                  0x1D00...0x1D25, 0x1E00...0x1EFF, 0x2C60...0x2C7F, 0xA720...0xA7FF, 0xFB00...0xFB06,
                  0xFF21...0xFF3A, 0xFF41...0xFF5A]),
        (.greek, [0x370...0x3FF, 0x1F00...0x1FFF]),
        (.cyrillic, [0x400...0x52F, 0x1C80...0x1C8F, 0x2DE0...0x2DFF, 0xA640...0xA69F]),
        (.armenian, [0x530...0x58F]),
        (.hebrew, [0x590...0x5FF]),
        (.arabic, [0x600...0x6FF, 0x750...0x77F, 0x8A0...0x8FF, 0xFB50...0xFDFF, 0xFE70...0xFEFF]),
        (.devanagari, [0x900...0x97F]),
        (.bengali, [0x980...0x9FF]),
        (.tamil, [0xB80...0xBFF]),
        (.thai, [0xE00...0xE7F]),
        (.georgian, [0x10A0...0x10FF, 0x1C90...0x1CBF, 0x2D00...0x2D2F]),
        (.hangul, [0x1100...0x11FF, 0x3130...0x318F, 0xA960...0xA97F, 0xAC00...0xD7FF, 0xFFA0...0xFFDC]),
        (.hiragana, [0x3041...0x309F]),
        (.katakana, [0x30A0...0x30FA, 0x30FD...0x30FF, 0x31F0...0x31FF, 0xFF66...0xFF9F]),
        (.han, [0x2E80...0x2FDF, 0x3005...0x3005, 0x3007...0x3007, 0x3021...0x3029, 0x3038...0x303B,
                0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF, 0x20000...0x2FA1F, 0x30000...0x3134F]),
        (.bopomofo, [0x3100...0x312F, 0x31A0...0x31BF]),
    ]

    /// nil for Common and Inherited characters (digits, hyphen, combining marks, marks of
    /// punctuation) which never count.
    static func script(of s: Unicode.Scalar) -> Script? {
        let v = s.value
        if v < 0x80 && !((0x41...0x5A).contains(v) || (0x61...0x7A).contains(v)) { return nil }
        if (0x300...0x36F).contains(v) || v == 0x30FB || v == 0x30FC { return nil }
        for (script, rs) in ranges where rs.contains(where: { $0.contains(v) }) { return script }
        return s.properties.isAlphabetic ? .other : nil
    }

    static func scripts(in label: String) -> Set<Script> {
        var out = Set<Script>()
        for s in label.unicodeScalars { if let sc = script(of: s) { out.insert(sc) } }
        return out
    }

    private static let allowedSets: [Set<Script>] = [
        [.latin, .han, .hiragana, .katakana],
        [.latin, .hangul, .han],
        [.latin, .han, .bopomofo],
    ]

    static func isMixed(_ scripts: Set<Script>) -> Bool {
        if scripts.count < 2 { return false }
        return !allowedSets.contains { scripts.isSubset(of: $0) }
    }
}
