import Foundation

/// One piece of a fast, or of a planned fast, on one calendar day, as positions on a 24-hour dial:
/// 0 is midnight at the top, 0.5 noon at the bottom, clockwise, 1 the next midnight back at the top.
///
/// Positions are wall-clock times, so noon sits at the bottom on every day, daylight-saving days
/// included. The hours shown under a date stay `DaySummary.seconds` (actual elapsed time).
public struct DayArc: Equatable, Sendable {
    public enum Kind: Equatable, Sendable { case actual, running, planned }
    public var fastID: UUID
    public var kind: Kind
    /// Where the piece starts on the dial, 0...1. 0 when it comes in from the day before.
    public var from: Double
    /// Where the piece ends on the dial, 0...1. 1 when it carries on past midnight.
    public var to: Double
    /// The timer was already running at this day's first midnight: the piece comes in along the
    /// link from the day before.
    public var fromPreviousDay: Bool
    /// The timer ran past this day's last midnight: the piece leaves along the link to the next day.
    /// A fast that ends exactly at midnight doesn't pass it, so it has no link.
    public var intoNextDay: Bool
}

extension HistoryMath {
    /// The pieces of fasts and planned fasts on `date`, earliest first. Actual fasts never overlap,
    /// so pieces never overlap either. Planned fasts appear only while they are still ahead, and
    /// only with a goal (an open-ended plan has no end to draw). A running fast ends at `now`.
    public static func arcs(on date: Date, archive: FastArchive, calendar: Calendar, now: Date) -> [DayArc] {
        guard let day = calendar.dateInterval(of: .day, for: date) else { return [] }
        var spans: [(id: UUID, kind: DayArc.Kind, start: Date, end: Date)] = archive.fasts.map {
            ($0.id, $0.end == nil ? .running : .actual, $0.start, $0.end ?? now)
        }
        for plan in archive.plans where plan.start >= now {
            guard let minutes = plan.goalMinutes, minutes > 0 else { continue }
            spans.append((plan.id, .planned, plan.start, plan.start.addingTimeInterval(Double(minutes) * 60)))
        }
        return spans.compactMap { span -> DayArc? in
            guard span.end > span.start, span.start < day.end, span.end > day.start else { return nil }
            let comesIn = span.start < day.start
            let goesOn = span.end > day.end
            let from = comesIn ? 0 : dialPosition(span.start, calendar: calendar)
            let to = goesOn || span.end == day.end ? 1 : dialPosition(span.end, calendar: calendar)
            // On the day clocks go back, a short fast inside the repeated hour can read backwards on
            // the wall clock; keep it a sliver at its start rather than wrapping round the dial.
            return DayArc(fastID: span.id, kind: span.kind, from: from, to: max(to, from),
                          fromPreviousDay: comesIn, intoNextDay: goesOn)
        }
        .sorted { $0.from < $1.from }
    }

    /// Wall-clock time of day as a fraction of 24 hours: 06:00 is 0.25, 18:00 is 0.75.
    public static func dialPosition(_ date: Date, calendar: Calendar) -> Double {
        let time = calendar.dateComponents([.hour, .minute, .second, .nanosecond], from: date)
        let seconds = Double((time.hour ?? 0) * 3_600 + (time.minute ?? 0) * 60 + (time.second ?? 0))
            + Double(time.nanosecond ?? 0) / 1_000_000_000
        return min(1, max(0, seconds / 86_400))
    }
}
