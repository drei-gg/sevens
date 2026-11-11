import SwiftUI
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
            VStack(alignment: .leading, spacing: 12) {
                Text(f("Day to explore")).font(.headline)
                DatePicker(f("Day to explore"), selection: $selected, displayedComponents: .date)
                    .labelsHidden().accessibilityLabel(f("Day to explore"))
            }.frame(maxWidth: .infinity, alignment: .leading)
            Text(f("Choose a day to read its fasting records and observations below.")).foregroundStyle(.secondary)
        } else {
        VStack(spacing: 8) {
            HStack(spacing: 0) {
                ForEach(0..<7) { offset in Text(symbols[(calendar.firstWeekday - 1 + offset) % 7]).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity) }
            }.accessibilityHidden(true)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 7), spacing: 6) {
                ForEach(dates, id: \.self) { date in
                    let summary = HistoryMath.day(date, archive: model.archive, calendar: calendar, now: Date())
                    Button { selected = date } label: {
                        VStack(spacing: 3) {
                            Text(date, format: .dateTime.day()).font(.body)
                            if summary.seconds > 0 {
                                Text((summary.seconds / 3_600).formatted(.number.precision(.fractionLength(0...1))))
                                    .font(.caption2).monospacedDigit().minimumScaleFactor(0.7)
                            } else { Text("–").font(.caption2).foregroundStyle(.tertiary) }
                            HStack(spacing: 3) {
                                if summary.journalCount > 0 { Circle().frame(width: 4, height: 4) }
                                if summary.planCount > 0 { Image(systemName: "clock").font(.system(size: 7)) }
                                if summary.isRestDay { Image(systemName: "leaf").font(.system(size: 7)) }
                            }.frame(height: 7)
                        }
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .foregroundStyle(calendar.isDate(date, equalTo: month, toGranularity: .month) ? .primary : .secondary)
                        .background(calendar.isDate(date, inSameDayAs: selected) ? Color.accentColor.opacity(0.16) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                        .overlay { if calendar.isDateInToday(date) { RoundedRectangle(cornerRadius: 8).stroke(.secondary, lineWidth: 1) } }
                    }.buttonStyle(.plain)
                    .accessibilityLabel(dayLabel(summary))
                    .accessibilityAddTraits(calendar.isDate(date, inSameDayAs: selected) ? .isSelected : [])
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
        VStack(alignment: .leading, spacing: 12) {
            Text(start.formatted(date: .abbreviated, time: .omitted) + " – " + calendar.date(byAdding: .day, value: -1, to: weekEnd)!.formatted(date: .abbreviated, time: .omitted))
                .font(.caption).foregroundStyle(.secondary)
            HStack(alignment: .top, spacing: 4) {
                VStack(spacing: 0) {
                    Color.clear.frame(height: 36)
                    ForEach(0..<7) { offset in
                        Text(calendar.veryShortStandaloneWeekdaySymbols[(calendar.firstWeekday - 1 + offset) % 7])
                            .font(.caption2).foregroundStyle(.secondary).frame(width: 18, height: 44)
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
                                    }.font(.caption2).foregroundStyle(.secondary).frame(height: 36).accessibilityHidden(true)
                                }
                                ForEach(slice) { day in
                                    Button { selected = day.date } label: {
                                        RoundedRectangle(cornerRadius: 4)
                                            .fill(day.seconds > 0 ? Color.accentColor.opacity(shade(day.seconds)) : Color.secondary.opacity(0.12))
                                            .frame(width: 28, height: 28)
                                            .overlay {
                                                if day.isRestDay { Image(systemName: "leaf").font(.caption2).foregroundStyle(.primary) }
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
                    }.padding(2)
                }.defaultScrollAnchor(.trailing)
            }.dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            HStack(spacing: 8) {
                ForEach([0, 6, 12, 18], id: \.self) { hours in
                    HStack(spacing: 3) {
                        RoundedRectangle(cornerRadius: 2).fill(hours == 0 ? Color.secondary.opacity(0.12) : Color.accentColor.opacity(shade(Double(hours) * 3_600)))
                            .frame(width: 10, height: 10)
                        Text(hours == 0 ? "0" : hours == 18 ? "18+" : "\(hours)").font(.caption2)
                    }
                }
                Text(f("hours")).font(.caption2).foregroundStyle(.secondary)
            }
            Text(f("Darker means more recorded time, not a better day. A dot marks a journal entry.")).font(.caption).foregroundStyle(.secondary)
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
