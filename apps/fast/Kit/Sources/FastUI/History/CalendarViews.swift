import SwiftUI
import SevenKit
import FastCore

struct MonthCalendar: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(FastModel.self) private var model
    let month: Date
    @Binding var selected: Date
    private var calendar: Calendar { .current }
    var body: some View {
        let dates = HistoryMath.monthGrid(containing: month, calendar: calendar)
        let symbols = calendar.veryShortStandaloneWeekdaySymbols
        if textSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: Tokens.Space.ms) {
                Text(f("Day to explore")).textRole(.rowTitle, emphasized: true)
                DatePicker(f("Day to explore"), selection: $selected, displayedComponents: .date)
                    .labelsHidden().accessibilityLabel(f("Day to explore"))
            }.frame(maxWidth: .infinity, alignment: .leading)
            Text(f("Choose a day to read its fasting records and observations below.")).foregroundStyle(Tokens.muted)
        } else {
        VStack(spacing: Tokens.Space.s) {
            HStack(spacing: 0) {
                ForEach(0..<7) { offset in Text(symbols[(calendar.firstWeekday - 1 + offset) % 7]).textRole(.caption).frame(maxWidth: .infinity) }
            }.accessibilityHidden(true)
            // Week rows, so a fast that runs past midnight can be drawn from one day's ring to the next.
            ForEach(0..<(dates.count / 7), id: \.self) { week in
                let row = Array(dates[(week * 7)..<(week * 7 + 7)])
                let now = Date()
                HStack(spacing: 0) {
                    ForEach(row, id: \.self) { date in dayCell(date) }
                }
                .overlay(alignment: .top) {
                    LinkedDayRings(days: row.map { HistoryMath.arcs(on: $0, archive: model.archive, calendar: calendar, now: now) })
                        .frame(height: LinkedDayRings.top + LinkedDayRings.diameter)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
        }
        }
    }

    private func dayCell(_ date: Date) -> some View {
        let summary = HistoryMath.day(date, archive: model.archive, calendar: calendar, now: Date())
        return Button { selected = date } label: {
            VStack(spacing: Tokens.Space.xs) {
                Text(date, format: .dateTime.day()).textRole(.body, emphasized: calendar.isDateInToday(date)).numeric()
                    .frame(width: LinkedDayRings.diameter, height: LinkedDayRings.diameter)
                if summary.seconds > 0 {
                    Text((summary.seconds / 3_600).formatted(.number.precision(.fractionLength(0...1))))
                        .textRole(.caption).monospacedDigit().minimumScaleFactor(0.7)
                } else { Text("–").textRole(.caption) }
                HStack(spacing: Tokens.Space.xs) {
                    if summary.journalCount > 0 { Circle().frame(width: 4, height: 4) }
                    if summary.planCount > 0 { Image(systemName: "clock").imageScale(.small).textRole(.caption) }
                    if summary.isRestDay { Image(systemName: "leaf").imageScale(.small).textRole(.caption) }
                }.frame(height: 7)
            }
            .padding(.top, LinkedDayRings.top)
            .frame(maxWidth: .infinity, minHeight: 76, alignment: .top)
            .foregroundStyle(calendar.isDate(date, equalTo: month, toGranularity: .month) ? Color.primary : Tokens.muted)
            .background(calendar.isDate(date, inSameDayAs: selected) ? Tokens.ink.opacity(0.12) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay { if calendar.isDateInToday(date) { RoundedRectangle(cornerRadius: 8).stroke(Tokens.ink, lineWidth: 1) } }
            .padding(.horizontal, Tokens.Space.xs / 2)
        }.buttonStyle(.plain)
        .accessibilityLabel(dayLabel(summary))
        .accessibilityAddTraits(calendar.isDate(date, inSameDayAs: selected) ? .isSelected : [])
    }
}

/// One week of 24-hour day rings: midnight at the top, noon at the bottom, clockwise. Each fast
/// is one unbroken stroke from its start to its end. Where its timer ran past midnight, the stroke
/// leaves the top of one ring along a straight line into the top of the next; days without a
/// midnight crossing have no line. Across a week edge the line runs off the row's side and comes
/// back in at the next row's other side. Planned fasts are dashed; a running fast ends in an open
/// dot at now. Where one fast comes in at the top and the next leaves through it, the later stroke
/// clears a small gap through the earlier one, so the two read as separate fasts.
struct LinkedDayRings: View {
    let days: [[DayArc]]
    static let diameter: CGFloat = 36
    static let line: CGFloat = 3.5
    static let top: CGFloat = Tokens.Space.xs

    var body: some View {
        Canvas { context, size in
            let column = size.width / CGFloat(max(days.count, 1))
            let radius = (Self.diameter - Self.line) / 2
            let centerY = Self.top + Self.diameter / 2
            let topY = centerY - radius
            func center(_ day: Int) -> CGPoint { CGPoint(x: column * (CGFloat(day) + 0.5), y: centerY) }
            func angle(_ position: Double) -> Angle { .degrees(position * 360 - 90) }
            func point(_ day: Int, _ position: Double) -> CGPoint {
                let c = center(day), a = angle(position).radians
                return CGPoint(x: c.x + radius * cos(a), y: c.y + radius * sin(a))
            }
            func arc(_ day: Int, _ piece: DayArc) -> Path {
                var path = Path()
                path.addArc(center: center(day), radius: radius, startAngle: angle(piece.from), endAngle: angle(piece.to), clockwise: false)
                return path
            }

            for day in days.indices {
                let c = center(day)
                context.stroke(Path(ellipseIn: CGRect(x: c.x - radius, y: c.y - radius, width: radius * 2, height: radius * 2)),
                               with: .color(Tokens.rule), lineWidth: Self.line)
            }

            // Fasts in the order they first appear in the week, so an earlier fast is drawn before
            // the one that follows it through the same midnight.
            var order: [UUID] = []
            for pieces in days { for piece in pieces where !order.contains(piece.fastID) { order.append(piece.fastID) } }

            context.drawLayer { layer in
                for id in order {
                    var path = Path()
                    var started = false
                    var kind = DayArc.Kind.actual
                    var last: (day: Int, piece: DayArc)?
                    for day in days.indices {
                        guard let piece = days[day].first(where: { $0.fastID == id }) else { continue }
                        kind = piece.kind
                        if !started {
                            if piece.fromPreviousDay {
                                // Carried over from last week: come in from the row's leading edge.
                                path.move(to: CGPoint(x: 0, y: topY))
                                path.addLine(to: CGPoint(x: center(day).x, y: topY))
                            } else {
                                path.move(to: point(day, piece.from))
                            }
                            started = true
                        }
                        path.addArc(center: center(day), radius: radius, startAngle: angle(piece.from), endAngle: angle(piece.to), clockwise: false)
                        if piece.intoNextDay {
                            path.addLine(to: CGPoint(x: day + 1 < days.count ? center(day + 1).x : size.width, y: topY))
                        }
                        // The handover gap: another fast came in through this day's top and this one leaves through it.
                        if piece.intoNextDay, !piece.fromPreviousDay,
                           days[day].contains(where: { $0.fastID != id && $0.fromPreviousDay && !$0.intoNextDay }) {
                            var eraser = layer
                            eraser.blendMode = .destinationOut
                            eraser.stroke(arc(day, piece), with: .color(.black), style: StrokeStyle(lineWidth: Self.line + 4, lineCap: .butt))
                        }
                        last = (day, piece)
                    }
                    let style = kind == .planned
                        ? StrokeStyle(lineWidth: Self.line / 2, lineCap: .round, dash: [3, 3])
                        : StrokeStyle(lineWidth: Self.line, lineCap: .round, lineJoin: .round)
                    layer.stroke(path, with: .color(Tokens.dataWarm), style: style)
                    if kind == .running, let last, !last.piece.intoNextDay {
                        let end = point(last.day, last.piece.to)
                        let dot = Path(ellipseIn: CGRect(x: end.x - Self.line * 1.2, y: end.y - Self.line * 1.2,
                                                         width: Self.line * 2.4, height: Self.line * 2.4))
                        layer.fill(dot, with: .color(Tokens.card))
                        layer.stroke(dot, with: .color(Tokens.dataWarm), lineWidth: 2)
                    }
                }
            }
        }
    }
}

struct HeatmapView: View {
    @Environment(FastModel.self) private var model
    let end: Date
    @Binding var selected: Date
    var body: some View {
        let calendar = Calendar.current
        let weekEnd = calendar.dateInterval(of: .weekOfYear, for: end)?.end ?? end
        let start = calendar.date(byAdding: .day, value: -91, to: weekEnd)!
        let days = HistoryMath.days(in: DateInterval(start: start, end: weekEnd), archive: model.archive, calendar: calendar, now: Date())
        VStack(alignment: .leading, spacing: Tokens.Space.ms) {
            Text(start.formatted(date: .abbreviated, time: .omitted) + " – " + calendar.date(byAdding: .day, value: -1, to: weekEnd)!.formatted(date: .abbreviated, time: .omitted))
                .textRole(.caption)
            HStack(alignment: .top, spacing: Tokens.Space.xs) {
                VStack(spacing: 0) {
                    Color.clear.frame(height: 36)
                    ForEach(0..<7) { offset in
                        Text(calendar.veryShortStandaloneWeekdaySymbols[(calendar.firstWeekday - 1 + offset) % 7])
                            .textRole(.caption).frame(width: 18, height: 44)
                    }
                }.frame(width: 18).accessibilityHidden(true)
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: 0) {
                        ForEach(0..<13) { week in
                            let slice = Array(days.dropFirst(week * 7).prefix(7))
                            VStack(spacing: 0) {
                                if let first = slice.first {
                                    VStack(spacing: 0) {
                                        Text(first.date, format: .dateTime.month(.abbreviated))
                                        Text(first.date, format: .dateTime.day())
                                    }.textRole(.caption).frame(height: 36).accessibilityHidden(true)
                                }
                                ForEach(slice) { day in
                                    Button { selected = day.date } label: {
                                        RoundedRectangle(cornerRadius: 4)
                                            .fill(day.seconds > 0 ? Tokens.dataWarm.opacity(shade(day.seconds)) : Tokens.rule)
                                            .frame(width: 28, height: 28)
                                            .overlay {
                                                if day.isRestDay { Image(systemName: "leaf").foregroundStyle(.primary).textRole(.caption) }
                                                else if day.journalCount > 0 { Circle().fill(.primary).frame(width: 4, height: 4) }
                                            }
                                            .overlay {
                                                if calendar.isDate(day.date, inSameDayAs: selected) {
                                                    RoundedRectangle(cornerRadius: 4).stroke(.primary, lineWidth: 2)
                                                }
                                            }
                                            .frame(width: 44, height: 44).contentShape(Rectangle())
                                    }.buttonStyle(.plain).accessibilityLabel(dayLabel(day))
                                }
                            }
                        }
                    }.padding(Tokens.Space.xs)
                }.defaultScrollAnchor(.trailing)
            }.dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            HStack(spacing: Tokens.Space.s) {
                ForEach([0, 6, 12, 18], id: \.self) { hours in
                    HStack(spacing: Tokens.Space.xs) {
                        RoundedRectangle(cornerRadius: 2).fill(hours == 0 ? Tokens.rule : Tokens.dataWarm.opacity(shade(Double(hours) * 3_600)))
                            .frame(width: 10, height: 10)
                        Text(hours == 0 ? "0" : hours == 18 ? "18+" : "\(hours)").textRole(.caption)
                    }
                }
                Text(f("hours")).textRole(.caption)
            }
            Text(f("Darker means more recorded time, not a better day. A dot marks a journal entry.")).textRole(.caption)
        }
    }
    private func shade(_ seconds: Double) -> Double {
        seconds < 6 * 3_600 ? 0.25 : seconds < 12 * 3_600 ? 0.45 : seconds < 18 * 3_600 ? 0.65 : 0.85
    }
}

private func dayLabel(_ day: DaySummary) -> String {
    [day.date.formatted(date: .complete, time: .omitted), FastFormat.duration(day.seconds),
     day.journalCount > 0 ? f("Journal entries") + ": \(day.journalCount)" : "",
     day.planCount > 0 ? f("Planned fasts") + ": \(day.planCount)" : "", day.isRestDay ? f("Rest day") : ""]
        .filter { !$0.isEmpty }.joined(separator: ", ")
}
