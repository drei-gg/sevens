import Foundation

/// A small ISO 8601 parser: `YYYY-MM-DDTHH:MM:SS[.fraction](Z|+HH:MM|-HH:MM|+HHMM)`.
/// Hand-rolled so Linux and iOS agree on fractions of any length and on offsets.
enum ISO8601Lite {
    static func date(from text: String) -> Date? {
        let b = Array(text.trimmingCharacters(in: .whitespaces).utf8)
        guard b.count >= 20, b.count <= 40 else { return nil }
        func num(_ from: Int, _ len: Int) -> Int? {
            guard from + len <= b.count else { return nil }
            var v = 0
            for i in from..<(from + len) {
                guard b[i] >= 0x30, b[i] <= 0x39 else { return nil }
                v = v * 10 + Int(b[i] - 0x30)
            }
            return v
        }
        guard b[4] == 0x2D, b[7] == 0x2D, b[10] == 0x54 || b[10] == 0x74 || b[10] == 0x20,
              b[13] == 0x3A, b[16] == 0x3A,
              let y = num(0, 4), let mo = num(5, 2), let d = num(8, 2),
              let h = num(11, 2), let mi = num(14, 2), let s = num(17, 2),
              (1...12).contains(mo), (1...31).contains(d), h < 24, mi < 60, s <= 60 else { return nil }
        var i = 19
        var fraction = 0.0
        if i < b.count, b[i] == 0x2E || b[i] == 0x2C {
            i += 1
            var scale = 0.1
            let start = i
            while i < b.count, b[i] >= 0x30, b[i] <= 0x39 {
                if i - start < 9 { fraction += Double(b[i] - 0x30) * scale; scale /= 10 }
                i += 1
            }
            if i == start { return nil }
        }
        guard i < b.count else { return nil }
        var offset = 0
        if b[i] == 0x5A || b[i] == 0x7A {
            i += 1
        } else if b[i] == 0x2B || b[i] == 0x2D {
            let sign = b[i] == 0x2B ? 1 : -1
            guard let oh = num(i + 1, 2) else { return nil }
            i += 3
            if i < b.count, b[i] == 0x3A { i += 1 }
            let om = i < b.count ? num(i, 2) : 0
            guard let om, oh < 24, om < 60 else { return nil }
            i += i < b.count ? 2 : 0
            offset = sign * (oh * 3600 + om * 60)
        } else {
            return nil
        }
        guard i == b.count, d <= daysInMonth(y, mo) else { return nil }
        let seconds = Double(daysFromCivil(y, mo, d)) * 86_400 + Double(h * 3600 + mi * 60 + s - offset)
        return Date(timeIntervalSince1970: seconds + fraction)
    }

    private static func daysInMonth(_ y: Int, _ m: Int) -> Int {
        switch m {
        case 2: return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// Days since 1970-01-01 (Howard Hinnant's civil algorithm).
    private static func daysFromCivil(_ year: Int, _ month: Int, _ day: Int) -> Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
