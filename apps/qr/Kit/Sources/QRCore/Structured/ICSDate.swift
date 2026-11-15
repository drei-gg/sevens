import Foundation

/// iCalendar date and duration values, plus the GMT civil-date arithmetic the encoder reuses.
enum ICSDate {
    enum Kind: Equatable {
        case allDay, utc, floating
        case zoned(String)
    }

    // Howard Hinnant's days-from-civil algorithms.
    static func daysFromCivil(_ y0: Int, _ m: Int, _ d: Int) -> Int {
        let y = m <= 2 ? y0 - 1 : y0
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    static func civilFromDays(_ z0: Int) -> (y: Int, m: Int, d: Int) {
        let z = z0 + 719468
        let era = (z >= 0 ? z : z - 146096) / 146097
        let doe = z - era * 146097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        return (yoe + era * 400 + (m <= 2 ? 1 : 0), m, d)
    }

    static func daysIn(_ y: Int, _ m: Int) -> Int {
        switch m {
        case 2: return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// The wall-clock time read as if it were GMT.
    static func wallAsGMT(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int, _ s: Int) -> Date {
        Date(timeIntervalSince1970: Double(daysFromCivil(y, mo, d) * 86400 + h * 3600 + mi * 60 + s))
    }

    /// GMT calendar fields of an instant (clamped to a sane range).
    static func gmtFields(_ date: Date) -> (y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) {
        let raw = date.timeIntervalSince1970
        let t = raw.isFinite ? Int(max(-1e12, min(1e12, raw.rounded(.down)))) : 0
        let days = t >= 0 ? t / 86400 : -((-t + 86399) / 86400)
        let rem = t - days * 86400
        let c = civilFromDays(days)
        return (c.y, c.m, c.d, rem / 3600, rem % 3600 / 60, rem % 60)
    }

    private static func pad(_ n: Int, _ w: Int) -> String {
        let s = String(n)
        return String(repeating: "0", count: max(0, w - s.count)) + s
    }

    static func format(_ date: Date, kind: Kind) -> String {
        let f = gmtFields(date)
        let day = pad(f.y, 4) + pad(f.mo, 2) + pad(f.d, 2)
        if kind == .allDay { return day }
        let time = day + "T" + pad(f.h, 2) + pad(f.mi, 2) + pad(f.s, 2)
        return kind == .utc ? time + "Z" : time
    }

    private static func number(_ s: ArraySlice<Unicode.Scalar>) -> Int? {
        var n = 0
        for c in s {
            guard c.value >= 48, c.value <= 57 else { return nil }
            n = n * 10 + Int(c.value - 48)
        }
        return s.isEmpty ? nil : n
    }

    /// `20261110`, `20261110T090000`, `20261110T090000Z` (seconds optional).
    static func parse(_ value: String, tzid: String?) -> (date: Date, kind: Kind)? {
        let sc = Array(StructuredText.trimmed(value).uppercased().unicodeScalars)
        guard sc.count >= 8, let y = number(sc[0..<4]), let mo = number(sc[4..<6]), let d = number(sc[6..<8]),
              (1...12).contains(mo), (1...daysIn(y, mo)).contains(d) else { return nil }
        if sc.count == 8 { return (wallAsGMT(y, mo, d, 0, 0, 0), .allDay) }
        guard sc[8] == "T" else { return nil }
        var rest = Array(sc[9...])
        var utc = false
        if rest.last == "Z" { utc = true; rest.removeLast() }
        guard rest.count == 4 || rest.count == 6,
              let h = number(rest[0..<2]), let mi = number(rest[2..<4]),
              h <= 23, mi <= 59 else { return nil }
        let s = rest.count == 6 ? number(rest[4..<6]) : 0
        guard let sec = s, sec <= 60 else { return nil }
        if utc { return (wallAsGMT(y, mo, d, h, mi, sec), .utc) }
        if let tzid, let tz = TimeZone(identifier: tzid) {
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = tz
            var dc = DateComponents()
            dc.year = y; dc.month = mo; dc.day = d; dc.hour = h; dc.minute = mi; dc.second = sec
            if let date = cal.date(from: dc) { return (date, .zoned(tz.identifier)) }
        }
        return (wallAsGMT(y, mo, d, h, mi, sec), .floating)
    }

    /// `PnW` or `PnDTnHnMnS` in seconds; negative durations are not supported.
    static func duration(_ value: String) -> TimeInterval? {
        let sc = Array(StructuredText.trimmed(value).uppercased().unicodeScalars)
        guard sc.first == "P" else { return nil }
        var total = 0.0
        var n: Int?
        var inTime = false
        var sawUnit = false
        for c in sc.dropFirst() {
            if c.value >= 48 && c.value <= 57 {
                let next = (n ?? 0) * 10 + Int(c.value - 48)
                guard next < 100_000_000 else { return nil }
                n = next
                continue
            }
            if c == "T" { inTime = true; continue }
            guard let v = n else { return nil }
            n = nil
            sawUnit = true
            switch (c, inTime) {
            case ("W", false): total += Double(v) * 604_800
            case ("D", false): total += Double(v) * 86_400
            case ("H", true): total += Double(v) * 3_600
            case ("M", true): total += Double(v) * 60
            case ("S", true): total += Double(v)
            default: return nil
            }
        }
        return n == nil && sawUnit ? total : nil
    }
}
