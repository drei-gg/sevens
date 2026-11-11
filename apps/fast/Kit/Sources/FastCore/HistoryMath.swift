import Foundation

public struct DaySummary: Equatable, Identifiable, Sendable {
    public var date: Date
    public var seconds: TimeInterval
    public var fastIDs: [UUID]
    public var journalCount: Int
    public var isRestDay: Bool
    public var planCount: Int
    public var id: Date { date }
}

public struct PeriodSummary: Equatable, Sendable {
    public var seconds: TimeInterval
    public var completedCount: Int
    public var averageDuration: TimeInterval?
    public var goalsMet: Int
    public var goalsCount: Int
    public var goalFraction: Double? { goalsCount == 0 ? nil : Double(goalsMet) / Double(goalsCount) }
}

public enum HistoryMath {
    /// End-exclusive boundaries prevent double-counting records ending exactly at midnight.
    public static func overlap(start: Date, end: Date, interval: DateInterval) -> TimeInterval {
        max(0, min(end, interval.end).timeIntervalSince(max(start, interval.start)))
    }

    public static func day(_ date: Date, archive: FastArchive, calendar: Calendar, now: Date) -> DaySummary {
        let start = calendar.startOfDay(for: date)
        let end = calendar.date(byAdding: .day, value: 1, to: start)!
        let interval = DateInterval(start: start, end: end)
        let touching = archive.fasts.filter { overlap(start: $0.start, end: $0.end ?? now, interval: interval) > 0 }
        return DaySummary(date: start,
            seconds: touching.reduce(0) { $0 + overlap(start: $1.start, end: $1.end ?? now, interval: interval) },
            fastIDs: touching.map(\.id),
            journalCount: archive.journal.filter { $0.date >= start && $0.date < end }.count,
            isRestDay: archive.restDays.contains { calendar.isDate($0.date, inSameDayAs: date) },
            planCount: archive.plans.filter { $0.start >= start && $0.start < end }.count)
    }

    public static func days(in interval: DateInterval, archive: FastArchive, calendar: Calendar, now: Date) -> [DaySummary] {
        var result: [DaySummary] = []
        var date = calendar.startOfDay(for: interval.start)
        while date < interval.end, result.count < 3_700 {
            result.append(day(date, archive: archive, calendar: calendar, now: now))
            guard let next = calendar.date(byAdding: .day, value: 1, to: date), next > date else { break }
            date = next
        }
        return result
    }

    public static func summary(in interval: DateInterval, archive: FastArchive, now: Date) -> PeriodSummary {
        let completed = archive.fasts.filter { fast in
            guard let end = fast.end else { return false }
            return end >= interval.start && end < interval.end
        }
        let duration = completed.reduce(0) { $0 + $1.duration(at: now) }
        return PeriodSummary(seconds: archive.fasts.reduce(0) {
            $0 + overlap(start: $1.start, end: $1.end ?? now, interval: interval)
        }, completedCount: completed.count,
        averageDuration: completed.isEmpty ? nil : duration / Double(completed.count),
        goalsMet: completed.filter { $0.metGoal == true }.count,
        goalsCount: completed.filter { $0.goalMinutes != nil }.count)
    }

    public static func monthGrid(containing date: Date, calendar: Calendar) -> [Date] {
        guard let month = calendar.dateInterval(of: .month, for: date) else { return [] }
        let weekday = calendar.component(.weekday, from: month.start)
        let leading = (weekday - calendar.firstWeekday + 7) % 7
        let start = calendar.date(byAdding: .day, value: -leading, to: month.start)!
        let length = calendar.dateComponents([.day], from: month.start, to: month.end).day!
        let count = ((length + leading + 6) / 7) * 7
        return (0..<count).compactMap { calendar.date(byAdding: .day, value: $0, to: start) }
    }

    public static func matches(_ fast: FastRecord, query: String) -> Bool {
        query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || fast.searchableText.localizedStandardContains(query)
    }
}
