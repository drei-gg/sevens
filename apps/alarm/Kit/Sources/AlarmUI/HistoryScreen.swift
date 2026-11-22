import SwiftUI
import Charts
import SevenKit
import AlarmCore

/// What you recorded: this week's tiles and a chart first, then every record grouped by day.
/// Only recorded actions count; a scheduled time passing is never shown as done (SPEC).
struct HistoryScreen: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var search = ""
    @State private var filter = false
    @State private var date = Date()
    @State private var editing: LoopEvent?

    var events: [LoopEvent] {
        model.archive.events.filter { (search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) || $0.note.localizedCaseInsensitiveContains(search)) && (!filter || Calendar.current.isDate($0.date, inSameDayAs: date)) }
            .sorted { $0.date > $1.date }
    }
    private var days: [DayGroup] {
        let calendar = Calendar.current
        return Dictionary(grouping: events) { calendar.startOfDay(for: $0.date) }
            .map { DayGroup(day: $0.key, events: $0.value) }.sorted { $0.day > $1.day }
    }

    var body: some View {
        List {
            if !model.archive.events.isEmpty && search.isEmpty {
                Section { summary.bareListRow() }
            }
            Section {
                Toggle(f("Show one day"), isOn: reduceMotion ? $filter : $filter.animation())
                if filter { DatePicker(f("Day"), selection: $date, in: ...Date(), displayedComponents: .date) }
            } footer: {
                Text(f("Only recorded actions appear here. An alarm being scheduled doesn’t prove you heard it or did the activity.")).textRole(.caption)
            }
            if events.isEmpty {
                ContentUnavailableView {
                    Label(search.isEmpty ? f("No recorded actions") : "No results for “\(search)”", systemImage: "calendar")
                } description: {
                    Text(search.isEmpty ? f("Completed missions, snoozes and check-ins appear here.") : f("Try another name or note."))
                }
                .bareListRow()
            }
            ForEach(days) { group in
                Section {
                    ForEach(group.events) { event in
                        Button { editing = event } label: { EventRow(event: event) }
                            .buttonStyle(.plain)
                            .swipeActions { Button(f("Delete"), role: .destructive) { Task { _ = await model.mutate { $0.events.removeAll { $0.id == event.id } } } } }
                    }
                } header: {
                    Text(dayTitle(group.day)).foregroundStyle(Tokens.muted)
                }
            }
        }
        .listSectionSpacing(.compact)
        .sevenScreenBackground()
        .navigationTitle(f("History"))
        .searchable(text: $search, prompt: f("Find a routine or note"))
        .sheet(item: $editing) { EventEditor(event: $0) }
    }

    // MARK: Summary

    /// Two tiles for the last seven days and a bar a day, in the time hue.
    private var summary: some View {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        let week = (0..<7).reversed().compactMap { calendar.date(byAdding: .day, value: -$0, to: today) }
        let start = week.first ?? today
        let recent = model.archive.events.filter { $0.date >= start }
        let missions = recent.filter { $0.kind == .completed }.count
        let counts = week.map { day in DayCount(day: day, count: recent.filter { calendar.isDate($0.date, inSameDayAs: day) }.count) }
        return VStack(spacing: Tokens.Space.ms) {
            StatGrid {
                StatTile(title: f("Last 7 days"), value: recent.count.formatted(), detail: f("recorded actions"))
                StatTile(title: f("Missions"), value: missions.formatted(), detail: f("completed"))
            }
            VStack(alignment: .leading, spacing: Tokens.Space.s) {
                Text(f("Recorded each day")).textRole(.caption, emphasized: true).textCase(.uppercase)
                Chart(counts) { item in
                    BarMark(x: .value("Day", item.day, unit: .day), y: .value("Records", item.count), width: .ratio(0.55))
                        .foregroundStyle(Tokens.dataWarm)
                        .cornerRadius(Tokens.Space.xs, style: .continuous)
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .day)) { _ in
                        AxisValueLabel(format: .dateTime.weekday(.narrow), centered: true).foregroundStyle(Tokens.muted)
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { _ in
                        AxisGridLine().foregroundStyle(Tokens.rule)
                        AxisValueLabel().foregroundStyle(Tokens.muted)
                    }
                }
                .frame(minHeight: 140)
                .accessibilityLabel(f("Recorded actions per day, last seven days"))
            }
            .padding(Tokens.Space.m).sevenCard()
        }
        .padding(.horizontal, Tokens.Space.m)
    }

    private func dayTitle(_ day: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return f("Today") }
        if calendar.isDateInYesterday(day) { return f("Yesterday") }
        return day.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())
    }
}

private struct DayGroup: Identifiable {
    let day: Date
    let events: [LoopEvent]
    var id: Date { day }
}

private struct DayCount: Identifiable {
    let day: Date
    let count: Int
    var id: Date { day }
}

/// A record: what happened, to which routine, and when, with the kind's symbol in a fixed column.
struct EventRow: View {
    let event: LoopEvent
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.ms) {
            Image(systemName: eventSymbol(event.kind)).foregroundStyle(Tokens.muted).frame(width: 28).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                Text(event.name).textRole(.rowTitle)
                Text(eventName(event.kind)).textRole(.secondary)
                if !event.note.isEmpty { Text(event.note).textRole(.caption).lineLimit(2) }
            }
            Spacer(minLength: Tokens.Space.s)
            Text(event.date.formatted(date: .omitted, time: .shortened)).textRole(.secondary).numeric()
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(f("Edits this record"))
    }
}

func eventName(_ kind: EventKind) -> String {
    switch kind {
    case .completed: f("Mission completed")
    case .dismissed: f("System dismissal")
    case .snoozed: f("Snoozed")
    case .emergencyStop: f("Emergency stop")
    case .manual: f("Manual check-in")
    case .followUpScheduled: f("Wake-up check scheduled")
    }
}
func eventSymbol(_ kind: EventKind) -> String {
    switch kind {
    case .completed: "checkmark.circle"
    case .dismissed: "xmark.circle"
    case .snoozed: "zzz"
    case .emergencyStop: "exclamationmark.octagon"
    case .manual: "hand.tap"
    case .followUpScheduled: "bell.badge"
    }
}

struct EventEditor: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var event: LoopEvent
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent(f("Routine"), value: event.name)
                    LabeledContent(f("Recorded as"), value: eventName(event.kind))
                }
                Section {
                    DatePicker(f("Recorded time"), selection: $event.date, in: ...Date())
                    TextField(f("Note"), text: $event.note, axis: .vertical)
                } footer: {
                    Text(f("Corrections are marked as manually recorded.")).textRole(.caption)
                }
            }
            .sevenScreenBackground()
            .navigationTitle(f("Edit record")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(f("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(f("Save")) { var copy = event; copy.kind = .manual; let saved = copy; Task { if await model.mutate({ a in if let i = a.events.firstIndex(where: { item in item.id == saved.id }) { a.events[i] = saved } }) { dismiss() } } }
                }
            }
        }
    }
}
