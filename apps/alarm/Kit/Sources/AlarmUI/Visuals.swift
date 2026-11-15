import SwiftUI
import UIKit
import AlarmCore
import SevenKit

// Pieces shared by Alarm's screens. `StatTile`, `StatGrid`, `bareListRow`, `ChipLabel` and
// `quickAddChip` are copied from Fast's polish pass (FastUI/Shared/Visuals.swift); they are
// candidates for SevenKit once the parallel passes have landed.

/// A labelled number on a card: the summary tiles at the top of History.
struct StatTile: View {
    let title: String
    let value: String
    var detail: String?
    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            Text(title).textRole(.caption, emphasized: true).textCase(.uppercase)
            Text(value).textRole(.section).numeric().lineLimit(2).minimumScaleFactor(0.7)
            if let detail { Text(detail).textRole(.caption) }
        }
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
        .padding(Tokens.Space.ms)
        .background(Tokens.card, in: RoundedRectangle(cornerRadius: Tokens.Radius.card, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// Two tiles a row, one a row at accessibility sizes.
struct StatGrid<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @ViewBuilder var content: Content
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.ms), count: textSize.isAccessibilitySize ? 1 : 2),
                  spacing: Tokens.Space.ms) { content }
    }
}

extension View {
    /// A list row that shows our own layout straight on paper, without the row's card.
    func bareListRow() -> some View {
        listRowBackground(Color.clear).listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
    }

    /// A capsule for one-tap actions people repeat (quick naps).
    func quickAddChip() -> some View {
        modifier(SevenButtonStyle(kind: .bordered)).controlSize(.large).buttonBorderShape(.capsule)
    }

    /// A small capsule inside a row (the mission an alarm uses, Log done).
    func rowChip() -> some View {
        modifier(SevenButtonStyle(kind: .bordered)).controlSize(.small).buttonBorderShape(.capsule)
    }
}

/// The label of a quick-action chip: the amount bold and tabular, its unit small beneath, so four
/// fit across a phone.
struct ChipLabel: View {
    let value: String
    let unit: String
    var body: some View {
        VStack(spacing: 0) {
            Text(value).textRole(.rowTitle, emphasized: true).numeric()
            Text(unit).textRole(.caption)
        }
        .lineLimit(1).minimumScaleFactor(0.8).frame(maxWidth: .infinity, minHeight: 28)
    }
}

/// Switches stay system green (polish guide §3.2 rule 4): SevenKit's root ink tint would otherwise
/// reach them, and a white thumb on dark-mode cream ink is 1.2:1. Applied once at the root.
struct SystemSwitchStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Toggle(configuration).toggleStyle(.switch).tint(nil)
    }
}

// MARK: Clock text

/// Times the way the system clock writes them, split so the hours and minutes can be big and
/// the AM/PM small, as clock apps do.
enum ClockFormat {
    /// Whether this person's clock is 12-hour (the `j` skeleton asks for a day period).
    static var uses12Hour: Bool {
        (DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .autoupdatingCurrent) ?? "").contains("a")
    }

    /// "7:00" and "AM" (or "07:00" and nil on a 24-hour clock): the system's own short time
    /// pattern with the day period taken out, so hours keep the locale's digits.
    static func parts(_ date: Date, timeZone: TimeZone = .autoupdatingCurrent) -> (time: String, period: String?) {
        let formatter = DateFormatter()
        formatter.locale = .autoupdatingCurrent
        formatter.timeZone = timeZone
        let pattern = DateFormatter.dateFormat(fromTemplate: "jmm", options: 0, locale: .autoupdatingCurrent) ?? "HH:mm"
        formatter.dateFormat = pattern.replacingOccurrences(of: "a", with: "")
            .trimmingCharacters(in: .whitespaces.union(CharacterSet(charactersIn: "\u{202F}\u{00A0}")))
        let time = formatter.string(from: date)
        guard uses12Hour else { return (time, nil) }
        var calendar = Calendar.autoupdatingCurrent
        calendar.timeZone = timeZone
        return (time, calendar.component(.hour, from: date) < 12 ? formatter.amSymbol : formatter.pmSymbol)
    }

    /// A clock time stored as minutes after midnight (a routine's shared time).
    static func parts(minute: Int) -> (time: String, period: String?) {
        parts(Date(timeIntervalSince1970: Double(minute) * 60), timeZone: TimeZone(secondsFromGMT: 0) ?? .gmt)
    }

    /// "in 14 hr, 46 min", rounded down to the minute.
    static func countdown(to date: Date, from now: Date) -> String {
        let seconds = date.timeIntervalSince(now)
        guard seconds >= 60 else { return String(localized: "in less than a minute") }
        let minutes = Int(seconds / 60)
        let text = Duration.seconds(minutes * 60).formatted(.units(allowed: [.days, .hours, .minutes], width: .abbreviated, maximumUnitCount: 2))
        return String(localized: "in \(text)")
    }

    /// "Today", "Tomorrow" or the weekday, for the day an alarm rings.
    static func day(_ date: Date, from now: Date) -> String {
        let calendar = Calendar.autoupdatingCurrent
        if calendar.isDate(date, inSameDayAs: now) { return String(localized: "Today") }
        if let tomorrow = calendar.date(byAdding: .day, value: 1, to: now), calendar.isDate(date, inSameDayAs: tomorrow) {
            return String(localized: "Tomorrow")
        }
        if date.timeIntervalSince(now) < 6 * 86_400 { return date.formatted(.dateTime.weekday(.wide)) }
        return date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }

    /// "5 minutes", "1 minute": plural-safe and localized.
    static func minutes(_ count: Int, width: Measurement<UnitDuration>.FormatStyle.UnitWidth = .wide) -> String {
        Measurement(value: Double(count), unit: UnitDuration.minutes).formatted(.measurement(width: width))
    }
}

/// A big clock time: the digits in one role, AM/PM smaller beside them.
struct ClockText: View {
    let parts: (time: String, period: String?)
    var role: TextRole = .title
    var periodRole: TextRole = .body
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.xs) {
            Text(parts.time).textRole(role).numeric().lineLimit(1).minimumScaleFactor(0.5)
            if let period = parts.period { Text(period).textRole(periodRole, emphasized: true).lineLimit(1) }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: Weekdays

/// Weekdays in the person's week order (Calendar weekday numbers, 1 = Sunday).
enum WeekOrder {
    static var days: [Int] {
        let first = Calendar.autoupdatingCurrent.firstWeekday
        return (0..<7).map { (first - 1 + $0) % 7 + 1 }
    }
    static func spoken(_ days: [Int]) -> String {
        let set = Set(days)
        if set.count == 7 { return String(localized: "Every day") }
        if set == [2, 3, 4, 5, 6] { return String(localized: "Weekdays") }
        if set == [1, 7] { return String(localized: "Weekends") }
        let names = Calendar.autoupdatingCurrent.weekdaySymbols
        return WeekOrder.days.filter(set.contains).map { names[$0 - 1] }.formatted(.list(type: .and))
    }
}

/// The week as initials, the days that ring in bold ink and the rest muted: M T W T F S S.
struct DayLetters: View {
    let days: [Int]
    var dimmed = false
    var body: some View {
        let symbols = Calendar.autoupdatingCurrent.veryShortWeekdaySymbols
        HStack(spacing: Tokens.Space.xs) {
            ForEach(WeekOrder.days, id: \.self) { day in
                let on = days.contains(day)
                Text(symbols[day - 1])
                    .foregroundStyle(on && !dimmed ? Color.primary : Tokens.muted)
                    .textRole(.caption, emphasized: on)
                    .frame(minWidth: 14)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(WeekOrder.spoken(days))
    }
}

// MARK: The next-alarm dial

/// A 24-hour dial, midnight at the top: a faint track, a mark where now is, and an arc in the
/// time hue from now to the next alarm when it rings within a day, ending in a knob. The night,
/// sunrise, noon and sunset marks are hidden from VoiceOver; the words in the middle carry it.
struct DayDial: View {
    let now: Date
    let next: Date?
    var width: CGFloat = 14
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let start = Self.fraction(now)
        let span = next.map { $0.timeIntervalSince(now) / 86_400 } ?? 0
        ZStack {
            Circle().stroke(Tokens.rule, lineWidth: width)
            if span > 0, span <= 1 {
                DialArc(start: start, length: span)
                    .stroke(Tokens.dataWarm, style: StrokeStyle(lineWidth: width, lineCap: .round))
            }
            GeometryReader { geometry in
                let radius = min(geometry.size.width, geometry.size.height) / 2
                let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
                // Now: a small dot on the track.
                Circle().fill(Tokens.muted).frame(width: width * 0.4, height: width * 0.4)
                    .position(Self.point(start, radius: radius, center: center))
                if span > 0, span <= 1 {
                    Circle().fill(Tokens.card).overlay(Circle().stroke(Tokens.dataWarm, lineWidth: width / 4))
                        .frame(width: width * 0.9, height: width * 0.9)
                        .position(Self.point(start + span, radius: radius, center: center))
                }
                ForEach(Array(["moon.stars", "sunrise", "sun.max", "sunset"].enumerated()), id: \.offset) { index, symbol in
                    Image(systemName: symbol).textRole(.caption)
                        .position(Self.point(Double(index) / 4, radius: radius - width - Tokens.Space.ms, center: center))
                }
            }
        }
        .padding(width / 2)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: span)
        .accessibilityHidden(true)
    }

    /// Where a time falls on the dial, 0 at midnight.
    static func fraction(_ date: Date) -> Double {
        let parts = Calendar.autoupdatingCurrent.dateComponents([.hour, .minute], from: date)
        return Double((parts.hour ?? 0) * 60 + (parts.minute ?? 0)) / 1_440
    }

    static func point(_ fraction: Double, radius: CGFloat, center: CGPoint) -> CGPoint {
        let angle = fraction * 2 * .pi - .pi / 2
        return CGPoint(x: center.x + radius * cos(angle), y: center.y + radius * sin(angle))
    }
}

/// An arc on the dial from `start` for `length` (both fractions of a day), drawn clockwise.
struct DialArc: Shape {
    var start: Double
    var length: Double
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.addArc(center: CGPoint(x: rect.midX, y: rect.midY), radius: min(rect.width, rect.height) / 2,
                    startAngle: .radians(start * 2 * .pi - .pi / 2), endAngle: .radians((start + length) * 2 * .pi - .pi / 2),
                    clockwise: false)
        return path
    }
}

// MARK: Missions

extension MissionKind {
    var title: String {
        switch self {
        case .none: String(localized: "None")
        case .arithmetic: String(localized: "Arithmetic")
        case .typing: String(localized: "Type a phrase")
        case .code: String(localized: "Scan a code")
        case .memory: String(localized: "Memory tiles")
        case .shake: String(localized: "Shake")
        case .walk: String(localized: "Walk")
        case .numberOrder: String(localized: "Number order")
        case .colorWord: String(localized: "Color clash")
        }
    }
    var symbol: String {
        switch self {
        case .none: "alarm"
        case .arithmetic: "plus.forwardslash.minus"
        case .typing: "keyboard"
        case .code: "qrcode.viewfinder"
        case .memory: "square.grid.3x3"
        case .shake: "iphone.radiowaves.left.and.right"
        case .walk: "figure.walk"
        case .numberOrder: "textformat.123"
        case .colorWord: "paintpalette"
        }
    }
}

extension Routine {
    /// The next time this routine rings or reminds, if it is on.
    func next(after now: Date) -> Date? {
        guard enabled else { return nil }
        return (try? ScheduleCompiler.occurrences(self, after: now, days: timing == .once ? 400 : 8))?.first
    }
}
