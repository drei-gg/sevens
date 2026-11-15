import SwiftUI
import FastCore
import SevenKit

struct HistoryScreen: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(FastModel.self) private var model
    @State private var mode = 0
    @State private var selected = Date()
    @State private var month = Date()
    @State private var query = ""
    @State private var filter = 0
    @State private var addPast = false
    @State private var journal = false
    @State private var jump = false
    private var calendar: Calendar { .current }

    var body: some View {
        VStack(spacing: 0) {
            Picker(f("History view"), selection: $mode) {
                Text(f("Calendar")).tag(0); Text(f("Heatmap")).tag(1); Text(f("List")).tag(2)
            }.pickerStyle(.segmented).padding(.horizontal).padding(.bottom, Tokens.Space.s)
            if mode != 2 {
                ScrollView {
                    SevenAdaptiveColumns(accessibilityText: textSize.isAccessibilitySize) {
                        calendarPanel.accessibilityElement(children: .contain).accessibilityIdentifier("history.calendarPanel")
                        daySection.accessibilityElement(children: .contain).accessibilityIdentifier("history.dayPanel")
                    }.padding().frame(maxWidth: 1200).frame(maxWidth: .infinity)
                }
            } else {
                List {
                    Section {
                        Picker(f("Filter"), selection: $filter) {
                            Text(f("All fasts")).tag(0); Text(f("Goal reached")).tag(1); Text(f("Shorter than planned")).tag(2); Text(f("Open ended")).tag(3)
                        }
                    }
                    if filtered.isEmpty {
                        ContentUnavailableView(f("No matching fasts"), systemImage: "calendar", description: Text(f("Add a past fast or try another search.")))
                    } else {
                        ForEach(filtered) { fast in NavigationLink { FastDetailScreen(id: fast.id) } label: { FastRow(fast: fast) } }
                    }
                }
            }
        }
        .sevenScreenBackground().navigationTitle(f("History"))
        .searchable(text: $query, prompt: f("Search intentions, reflections & tags"))
        .onChange(of: query) { _, new in if !new.isEmpty { mode = 2 } }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                GatedButton(feature: f("Add a past fast"), action: { addPast = true }) { Image(systemName: "plus").accessibilityLabel(f("Add past fast")) }
            }
        }
        .sheet(isPresented: $jump) {
            NavigationStack {
                Form { DatePicker(f("Date"), selection: $month, displayedComponents: .date).datePickerStyle(.wheel) }
                    .sevenScreenBackground().navigationTitle(f("Jump to date")).navigationBarTitleDisplayMode(.inline)
                    .toolbar { Button(f("Done")) { selected = month; jump = false } }
            }.presentationDetents([.medium])
        }
        .sheet(isPresented: $addPast) { FastEditor(mode: .past(selected)) }
        .sheet(isPresented: $journal) { JournalEditor(date: min(selected, Date())) }
    }
    private var calendarPanel: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.ms) {
            if !textSize.isAccessibilitySize {
                HStack {
                    Button { shift(-1) } label: { Image(systemName: "chevron.left").frame(minWidth: 44, minHeight: 44) }
                        .accessibilityLabel(mode == 0 ? f("Previous month") : f("Previous thirteen weeks"))
                    Spacer()
                    Button { jump = true } label: { Text(month, format: .dateTime.month(.wide).year()).textRole(.rowTitle, emphasized: true) }.accessibilityLabel(f("Jump to date"))
                    Spacer()
                    Button { shift(1) } label: { Image(systemName: "chevron.right").frame(minWidth: 44, minHeight: 44) }
                        .accessibilityLabel(mode == 0 ? f("Next month") : f("Next thirteen weeks"))
                }
            } else if mode == 1 {
                VStack(alignment: .leading, spacing: Tokens.Space.ms) {
                    Text(f("Period ending")).textRole(.rowTitle, emphasized: true)
                    DatePicker(f("Period ending"), selection: $month, displayedComponents: .date)
                        .labelsHidden().accessibilityLabel(f("Period ending"))
                }
            }
            if mode == 0 { MonthCalendar(month: month, selected: $selected) }
            else { HeatmapView(end: month, selected: $selected) }
            Button(f("Back to today")) { month = Date(); selected = Date() }.foregroundStyle(.tint).textRole(.secondary)
            Text(f("Hours are split at midnight in your current time zone:") + " " + TimeZone.current.identifier)
                .textRole(.caption)
        }
    }
    private var filtered: [FastRecord] {
        model.archive.fasts.filter { fast in
            HistoryMath.matches(fast, query: query) && (filter == 0 || filter == 1 && fast.metGoal == true
                || filter == 2 && fast.metGoal == false || filter == 3 && fast.goalMinutes == nil)
        }.sorted { $0.start > $1.start }
    }
    private func shift(_ direction: Int) {
        month = calendar.date(byAdding: mode == 0 ? .month : .day, value: direction * (mode == 0 ? 1 : 91), to: month) ?? month
        selected = month
    }
    @ViewBuilder private var daySection: some View {
        let summary = HistoryMath.day(selected, archive: model.archive, calendar: calendar, now: Date())
        GroupBox {
            VStack(alignment: .leading, spacing: Tokens.Space.m) {
            LabeledContent(f("Recorded fasting"), value: FastFormat.duration(summary.seconds))
            if summary.isRestDay { Label(f("Rest day"), systemImage: "leaf") }
            ForEach(model.archive.fasts.filter { summary.fastIDs.contains($0.id) }.sorted { $0.start < $1.start }) { fast in
                NavigationLink { FastDetailScreen(id: fast.id) } label: { FastRow(fast: fast) }
            }
            ForEach(model.archive.journal.filter { calendar.isDate($0.date, inSameDayAs: selected) }) { entry in
                NavigationLink { JournalDetailScreen(id: entry.id) } label: { JournalRow(entry: entry) }
            }
            ForEach(model.archive.plans.filter { calendar.isDate($0.start, inSameDayAs: selected) }) { plan in
                NavigationLink { PlansScreen() } label: {
                    Label { Text(f("Planned") + " · " + plan.start.formatted(date: .omitted, time: .shortened) + " · " + FastFormat.goal(plan.goalMinutes)) } icon: { Image(systemName: "calendar.badge.clock") }
                }
            }
            ForEach(model.archive.metrics.filter { calendar.isDate($0.date, inSameDayAs: selected) && ($0.kind != .weight || model.archive.settings.showWeight) }) { entry in
                NavigationLink { MetricHistoryScreen(kind: entry.kind) } label: {
                    LabeledContent(FastFormat.kind(entry.kind), value: Units.display(entry.value, kind: entry.kind, settings: model.archive.settings).formatted(.number.precision(.fractionLength(0...1))) + " " + FastFormat.unit(entry.kind, settings: model.archive.settings))
                }
            }
            if summary.fastIDs.isEmpty && summary.journalCount == 0 && summary.planCount == 0 && !model.archive.metrics.contains(where: { calendar.isDate($0.date, inSameDayAs: selected) }) {
                Text(f("Nothing recorded for this day.")).foregroundStyle(Tokens.muted)
            }
            if summary.isRestDay {
                Button { Task { await toggleRest() } } label: { Label(f("Remove rest day"), systemImage: "leaf") }
            } else {
                GatedButton(feature: f("Rest day"), action: { Task { await toggleRest() } }) {
                    Label(f("Mark as a rest day"), systemImage: "leaf")
                }
            }
            if selected <= Date() {
                GatedButton(feature: f("Journal"), action: { journal = true }) { Label(f("Add a check-in"), systemImage: "square.and.pencil") }
            }
            }.frame(maxWidth: .infinity, alignment: .leading)
        } label: { Text(selected, format: .dateTime.weekday(.wide).month().day()) }
    }
    private func toggleRest() async {
        let date = selected; let cal = calendar
        _ = await model.change { archive in
            if archive.restDays.contains(where: { cal.isDate($0.date, inSameDayAs: date) }) {
                archive.restDays.removeAll { cal.isDate($0.date, inSameDayAs: date) }
            } else { archive.restDays.append(RestDay.on(date)) }
        }
    }
}

struct FastRow: View {
    let fast: FastRecord
    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            HStack {
                Text(FastFormat.duration(fast.duration(at: Date()))).textRole(.rowTitle, emphasized: true).monospacedDigit()
                Spacer()
                if fast.end == nil { Label(f("Running"), systemImage: "timer").textRole(.caption) }
                else if fast.metGoal == true { Image(systemName: "checkmark.circle").foregroundStyle(Tokens.muted).accessibilityLabel(f("Goal reached")) }
            }
            Text(fast.start.formatted(date: .abbreviated, time: .shortened) + " – " + (fast.end?.formatted(date: .abbreviated, time: .shortened) ?? f("Now")))
                .textRole(.caption)
            if !fast.intention.isEmpty { Text(fast.intention).textRole(.secondary).lineLimit(2) }
        }.padding(.vertical, Tokens.Space.xs).accessibilityElement(children: .combine)
    }
}
