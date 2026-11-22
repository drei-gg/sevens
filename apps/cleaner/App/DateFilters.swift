import SwiftUI
import CleanerCore
import SevenKit

extension MediaDateFilter {
    static let choices: [Self] = [.any, .day, .month, .year, .anniversary]
    var title: String {
        switch self {
        case .any: "Any date"
        case .day: "On a date"
        case .month: "In a month"
        case .year: "In a year"
        case .anniversary: "On this day"
        }
    }
}
func mediaCalendarDate(_ date: Date, zone: TimeZone = .current) -> MediaCalendarDate {
    var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
    let parts = calendar.dateComponents([.era, .year, .month, .day], from: date)
    let year = parts.era == 0 ? 1 - (parts.year ?? 1) : parts.year ?? 1
    return MediaCalendarDate(year: Int32(clamping: year), month: UInt32(parts.month ?? 1), day: UInt32(parts.day ?? 1))
}
func matchesDate(_ value: Date?, mode: MediaDateFilter, reference: Date) -> Bool {
    mediaDateMatches(filter: mode, recorded: value.map { mediaCalendarDate($0) }, reference: mediaCalendarDate(reference))
}
func dateSummary(_ mode: MediaDateFilter, date: Date) -> String {
    if mode == .any { return "Any date" }
    if mode == .year { return String(mediaCalendarDate(date).year) }
    let formatter = DateFormatter(); formatter.locale = .autoupdatingCurrent; formatter.calendar = Calendar(identifier: .gregorian); formatter.timeZone = .current
    if mode == .month { formatter.dateFormat = "LLLL yyyy" } else { formatter.dateStyle = .medium }
    let label = formatter.string(from: date)
    return mode == .anniversary ? "On this day: \(label)" : label
}
struct DateFilterEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var mode: MediaDateFilter
    @State private var reference: Date
    let apply: (MediaDateFilter, Date) -> Void
    init(mode: MediaDateFilter, reference: Date, apply: @escaping (MediaDateFilter, Date) -> Void) {
        _mode = State(initialValue: mode); _reference = State(initialValue: reference); self.apply = apply
    }
    var body: some View {
        NavigationStack {
            Form {
                DateFilterFields(mode: $mode, reference: $reference)

            }
            .navigationTitle("Date filter").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Apply") { apply(mode, reference); dismiss() }.accessibilityIdentifier("cleaner-date-apply") }
            }
        }
    }
}

struct DateFilterFields: View {
    @Binding var mode: MediaDateFilter
    @Binding var reference: Date
    var showReviewNote = true
    var dateMeaning = "Uses each file’s library date in this device’s current time zone. Files without a date appear only with Any date."
    var body: some View {
        Group {
                Picker("Match", selection: $mode) { ForEach(MediaDateFilter.choices, id: \.self) { Text($0.title).tag($0) } }
                    .accessibilityIdentifier("cleaner-date-mode")
                if mode != .any {
                    DatePicker(mode == .month ? "Date in the month" : mode == .year ? "Date in the year" : "Reference date", selection: $reference, displayedComponents: .date)
                        .environment(\.calendar, Calendar(identifier: .gregorian)).accessibilityIdentifier("cleaner-date-reference")
                }
                Text(mode == .anniversary ? "The same month and day in earlier years. The reference year and later years are excluded; February 29 is not moved to another day." : dateMeaning).textRole(.secondary)
                Text(showReviewNote ? "Dates use the Gregorian calendar. Changing this filter does not change selections or review decisions." : "Dates use the Gregorian calendar.").textRole(.secondary)
                Button("Clear date filter") { mode = .any }
        }
    }
}
