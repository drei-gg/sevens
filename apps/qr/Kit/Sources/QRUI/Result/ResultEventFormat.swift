import Foundation
import QRCore

/// Event times (see `EventInfo`): UTC and TZID times are absolute instants, shown in the
/// device's time zone. Floating and all-day times hold the wall-clock fields read as GMT, so
/// they are formatted with a GMT calendar and shown as they were written (i.e. as local).
enum ResultEventFormat {
    static func isWallClock(_ event: EventInfo) -> Bool { event.isAllDay || event.isFloating }

    private static func gregorian(_ zone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return calendar
    }

    private static func format(_ date: Date, date dateStyle: DateFormatter.Style, time timeStyle: DateFormatter.Style,
                               zone: TimeZone, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = zone
        formatter.dateStyle = dateStyle
        formatter.timeStyle = timeStyle
        return formatter.string(from: date)
    }

    /// "Tuesday, 12 November 2026, 14:00 – 15:00", or the all-day date(s). Nil without a start.
    static func timeText(_ event: EventInfo, locale: Locale = .current, timeZone: TimeZone = .current) -> String? {
        guard let start = event.start else { return nil }
        let zone = isWallClock(event) ? TimeZone.gmt : timeZone
        let calendar = gregorian(zone)
        let separator = " – "
        if event.isAllDay {
            let first = format(start, date: .full, time: .none, zone: zone, locale: locale)
            // iCalendar's DTEND for a date is the day after the last one.
            guard let end = event.end, let last = calendar.date(byAdding: .day, value: -1, to: end),
                  !calendar.isDate(last, inSameDayAs: start), last > start else { return first }
            return first + separator + format(last, date: .full, time: .none, zone: zone, locale: locale)
        }
        let day = format(start, date: .full, time: .none, zone: zone, locale: locale)
        let startTime = format(start, date: .none, time: .short, zone: zone, locale: locale)
        guard let end = event.end, end > start else { return day + ", " + startTime }
        if calendar.isDate(end, inSameDayAs: start) {
            return day + ", " + startTime + separator + format(end, date: .none, time: .short, zone: zone, locale: locale)
        }
        return day + ", " + startTime + separator
            + format(end, date: .full, time: .short, zone: zone, locale: locale)
    }

    /// For an absolute time given in another zone: the start in that zone, so the person can
    /// see the organiser's clock. Nil when there is nothing to add.
    static func organiserText(_ event: EventInfo, locale: Locale = .current, timeZone: TimeZone = .current) -> String? {
        guard !isWallClock(event), let start = event.start, let id = event.timeZoneID,
              let zone = TimeZone(identifier: id), zone.secondsFromGMT(for: start) != timeZone.secondsFromGMT(for: start)
        else { return nil }
        let time = format(start, date: .none, time: .short, zone: zone, locale: locale)
        return String(localized: "result.event.organiserTime", defaultValue: "\(time) in \(id)", bundle: .module)
    }

    /// Reads GMT wall-clock fields and returns the same fields in `timeZone`.
    static func localDate(fromWallClock date: Date, timeZone: TimeZone = .current) -> Date {
        let fields = gregorian(.gmt).dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return gregorian(timeZone).date(from: fields) ?? date
    }

    /// Dates for an `EKEvent`: wall-clock times become local; a missing end is one hour after the
    /// start (a day for all-day); an all-day end is the last day (EventKit's inclusive end).
    static func calendarDates(_ event: EventInfo, timeZone: TimeZone = .current) -> (start: Date, end: Date, allDay: Bool)? {
        guard let rawStart = event.start else { return nil }
        let wall = isWallClock(event)
        let start = wall ? localDate(fromWallClock: rawStart, timeZone: timeZone) : rawStart
        let calendar = gregorian(timeZone)
        if event.isAllDay {
            var end = start
            if let rawEnd = event.end {
                let local = localDate(fromWallClock: rawEnd, timeZone: timeZone)
                if let last = calendar.date(byAdding: .day, value: -1, to: local), last > start { end = last }
            }
            return (start, end, true)
        }
        var end = start.addingTimeInterval(3600)
        if let rawEnd = event.end {
            let candidate = wall ? localDate(fromWallClock: rawEnd, timeZone: timeZone) : rawEnd
            if candidate > start { end = candidate }
        }
        return (start, end, false)
    }
}
