import Foundation

public enum ScheduleCompiler {
    /// A stable UUID in the routine namespace; never uses Swift's randomized Hasher.
    public static func identifier(_ routine: UUID, key: String) -> UUID {
        let bytes = Array((routine.uuidString + ":" + key).utf8)
        func hash(_ seed: UInt64) -> UInt64 { bytes.reduce(seed) { ($0 ^ UInt64($1)) &* 1099511628211 } }
        let a = hash(14695981039346656037), b = hash(7809847782465536322)
        let hex = String(format: "%016llx%016llx", a, b)
        let s = Array(hex)
        return UUID(uuidString: String(s[0..<8])+"-"+String(s[8..<12])+"-"+String(s[12..<16])+"-"+String(s[16..<20])+"-"+String(s[20..<32]))!
    }
    public static func registrations(_ routine: Routine, now: Date = Date()) throws -> [Registration] {
        try routine.validate()
        guard routine.enabled else { return [] }
        if routine.timing == .once {
            guard routine.once > now else { return [] }
            return [Registration(id: identifier(routine.id, key: "once"), routine: routine, date: routine.once)]
        }
        var daysByMinute: [Int: Set<Int>] = [:]
        if routine.timing == .weekly {
            for slot in routine.slots { daysByMinute[slot.minute, default: []].formUnion(slot.weekdays) }
        } else {
            let end = routine.windowEnd > routine.windowStart ? routine.windowEnd : routine.windowEnd + 1440
            // End is inclusive only when exactly reached by an interval.
            for offset in stride(from: routine.windowStart, through: end, by: routine.intervalMinutes) {
                let shifted = routine.windowDays.map { (($0 - 1 + offset / 1440) % 7) + 1 }
                daysByMinute[offset % 1440, default: []].formUnion(shifted)
            }
        }
        guard daysByMinute.count <= 96 else { throw LoopError.invalid("This loop creates more than 96 different alarm times. Increase its interval or shorten the window.") }
        return daysByMinute.keys.sorted().map { minute in
            Registration(id: identifier(routine.id, key: "minute:\(minute)"), routine: routine, minute: minute, weekdays: daysByMinute[minute]!.sorted())
        }
    }
    public static func occurrences(_ routine: Routine, after now: Date, days: Int = 7, calendar: Calendar = .current) throws -> [Date] {
        let registrations = try registrations(routine, now: now)
        let limit = calendar.date(byAdding: .day, value: days, to: now)!
        var dates = Set<Date>()
        for registration in registrations {
            if let date = registration.date { if date <= limit { dates.insert(date) }; continue }
            guard let minute = registration.minute else { continue }
            for weekday in registration.weekdays {
                var cursor = now
                for _ in 0..<(days / 7 + 2) {
                    let components = DateComponents(hour: minute / 60, minute: minute % 60, second: 0, weekday: weekday)
                    guard let next = calendar.nextDate(after: cursor, matching: components, matchingPolicy: .nextTime, repeatedTimePolicy: .first), next <= limit else { break }
                    dates.insert(next); cursor = next
                }
            }
        }
        return dates.sorted()
    }
}
public struct ArithmeticChallenge: Equatable, Sendable {
    public let left: Int
    public let right: Int
    public init(left: Int = Int.random(in: 2...25), right: Int = Int.random(in: 2...25)) { self.left = left; self.right = right }
    public func accepts(_ answer: String) -> Bool { Int(answer.trimmingCharacters(in: .whitespacesAndNewlines)) == left + right }
}
public enum MissionCheck {
    public static func phrase(_ actual: String, expected: String) -> Bool {
        actual.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping == expected.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping
    }
    public static func code(_ actual: String, expected: String) -> Bool { !expected.isEmpty && actual == expected }
}
