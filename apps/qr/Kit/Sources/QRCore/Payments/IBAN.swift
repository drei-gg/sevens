public enum IBAN {
    /// Spaces removed and uppercased; nil when it is not 15–34 letters/digits shaped as
    /// two country letters, two check digits, then a basic part.
    static func normalized(_ iban: String) -> String? {
        let s = iban.filter { $0 != " " }.uppercased()
        let u = Array(s.utf8)
        guard (15...34).contains(u.count) else { return nil }
        for (i, b) in u.enumerated() {
            let isLetter = (65...90).contains(b), isDigit = (48...57).contains(b)
            switch i {
            case 0, 1: if !isLetter { return nil }
            case 2, 3: if !isDigit { return nil }
            default: if !isLetter && !isDigit { return nil }
            }
        }
        return s
    }

    /// ISO 13616 mod-97, computed digit by digit (no big integers). Length and shape only;
    /// no per-country length table.
    public static func isValid(_ iban: String) -> Bool {
        guard let s = normalized(iban) else { return false }
        return mod97Is1(Array(s.utf8.dropFirst(4)) + Array(s.utf8.prefix(4)))
    }

    /// True when the letters-as-numbers (A=10…Z=35) reading of `bytes` is 1 mod 97.
    static func mod97Is1(_ bytes: [UInt8]) -> Bool {
        var rem = 0
        for b in bytes {
            switch b {
            case 48...57: rem = (rem * 10 + Int(b) - 48) % 97
            case 65...90: rem = (rem * 100 + Int(b) - 55) % 97
            default: return false
            }
        }
        return rem == 1
    }
}
