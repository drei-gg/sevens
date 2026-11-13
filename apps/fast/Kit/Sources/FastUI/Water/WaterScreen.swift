import SwiftUI
import Charts
import FastCore
import SevenKit

func waterAmount(_ milliliters: Double, settings: TrackerSettings) -> String {
    Units.display(milliliters, kind: .water, settings: settings).formatted(.number.precision(.fractionLength(0...1)))
        + " " + FastFormat.unit(.water, settings: settings)
}

/// "of 2,000 mL" under a total when there's a goal; how many drinks when there isn't.
private func waterDetail(total: Double, entries: Int, settings: TrackerSettings) -> String {
    if let goal = settings.water.goalMilliliters {
        return f("of") + " " + waterAmount(goal, settings: settings)
    }
    return String(localized: "\(entries) drinks", bundle: .module)
}

/// Shown instead of a fill while no daily goal is set: the glass has nothing to fill toward
/// until there is one. Opens the goal settings; it never suggests an amount.
private struct SetWaterGoalLink: View {
    var body: some View {
        NavigationLink { WaterSettingsScreen() } label: {
            Label(f("Set a daily goal"), systemImage: "target").textRole(.caption)
        }
        .foregroundStyle(.tint)
        .accessibilityHint(f("Your glass fills toward it"))
        .accessibilityIdentifier("water.setGoal")
    }
}

/// The glass amount and a way to log another amount: the quick adds on Today and in Water.
private struct WaterQuickAdds: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dynamicTypeSize) private var textSize
    var day = Date()
    var quickIdentifier = "water.quickAdd"
    var otherIdentifier = "water.otherAmount"
    var added: (UUID) -> Void = { _ in }
    @State private var other = false
    var body: some View {
        let layout = textSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: Tokens.Space.s)) : AnyLayout(HStackLayout(spacing: Tokens.Space.s))
        layout {
            GatedButton(feature: f("Water"), action: { Task { await add() } }) {
                ChipLabel(title: waterAmount(model.archive.settings.water.quickAddMilliliters, settings: model.archive.settings))
            }.quickAddChip().disabled(model.busy)
                .accessibilityLabel(f("Add") + " " + waterAmount(model.archive.settings.water.quickAddMilliliters, settings: model.archive.settings))
                .accessibilityIdentifier(quickIdentifier)
            GatedButton(feature: f("Water"), action: { other = true }) {
                ChipLabel(title: f("Other amount"), systemImage: "square.and.pencil")
            }.quickAddChip().accessibilityIdentifier(otherIdentifier)
        }
        .sheet(isPresented: $other) { MetricEditor(kind: .water, initialDate: day) }
    }
    private func add() async {
        let calendar = Calendar.current
        var date = Date()
        if !calendar.isDateInToday(day) {
            // A past day gets the drink at the current time of day on that date.
            let time = calendar.dateComponents([.hour, .minute], from: date)
            date = calendar.date(bySettingHour: time.hour ?? 12, minute: time.minute ?? 0, second: 0, of: day) ?? day
        }
        let entry = MetricEntry(date: date, kind: .water, value: model.archive.settings.water.quickAddMilliliters)
        if await model.saveMetric(entry) { added(entry.id) }
    }
}

struct WaterCard: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var lastAdded: UUID?
    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: Tokens.Space.m) {
                NavigationLink { WaterScreen() } label: {
                    HStack(spacing: Tokens.Space.s) {
                        Image(systemName: "drop.fill").foregroundStyle(Tokens.dataCool).accessibilityHidden(true)
                        Text(f("Water today")).textRole(.rowTitle, emphasized: true)
                        Spacer(minLength: 0)
                        if !textSize.isAccessibilitySize { Text(f("History")).textRole(.caption) }
                        Image(systemName: "chevron.right").textRole(.caption).accessibilityHidden(true)
                    }.contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(f("Water history & settings"))
                .accessibilityIdentifier("water.open")
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    let settings = model.archive.settings
                    let total = WaterHistory.total(model.archive.metrics, on: context.date)
                    let entries = WaterHistory.entries(model.archive.metrics, on: context.date).count
                    HStack(spacing: Tokens.Space.m) {
                        WaterLevel(level: settings.water.goalMilliliters.map { total / $0 }).frame(width: 64)
                        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                            Text(waterAmount(total, settings: settings)).textRole(.hero).numeric()
                                .accessibilityIdentifier("water.todayTotal")
                            Text(waterDetail(total: total, entries: entries, settings: settings)).textRole(.secondary)
                            if settings.water.goalMilliliters == nil { SetWaterGoalLink() }
                        }
                        Spacer(minLength: 0)
                    }
                    .accessibilityElement(children: .contain)
                }
                WaterQuickAdds(added: { lastAdded = $0 })
                if let id = lastAdded, model.archive.metrics.contains(where: { $0.id == id }) {
                    Button(f("Undo last drink")) {
                        Task { if await model.change({ $0.metrics.removeAll { $0.id == id } }) { lastAdded = nil } }
                    }.disabled(model.busy).accessibilityIdentifier("water.undo")
                }
                if model.archive.settings.water.remindersEnabled {
                    Button(model.archive.settings.water.remindersPaused ? f("Resume water reminders") : f("Pause water reminders")) {
                        Task {
                            let resuming = model.archive.settings.water.remindersPaused
                            if await model.change({ $0.settings.water.remindersPaused = !resuming }), resuming {
                                _ = await model.reminders.requestPermission(); await model.updateServices()
                            }
                        }
                    }.disabled(model.busy).accessibilityIdentifier("water.pause")
                    if model.archive.settings.water.remindersPaused {
                        Text(f("Paused until you resume.")).textRole(.caption)
                    }
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct WaterScreen: View {
    @Environment(FastModel.self) private var model
    @State private var day = Date()
    @State private var edit: MetricEntry?
    @State private var deleting: MetricEntry?
    @State private var selected: Date?
    var body: some View {
        let settings = model.archive.settings
        let total = WaterHistory.total(model.archive.metrics, on: day)
        let entries = WaterHistory.entries(model.archive.metrics, on: day)
        let week = WaterHistory.days(model.archive.metrics, ending: Date(), count: 7)
        List {
            Section {
                VStack(spacing: Tokens.Space.m) {
                    dayStepper
                    WaterLevel(level: settings.water.goalMilliliters.map { total / $0 }).frame(maxWidth: 168)
                    VStack(spacing: Tokens.Space.xs) {
                        Text(waterAmount(total, settings: settings)).textRole(.display).numeric()
                            .lineLimit(1).minimumScaleFactor(0.5)
                            .accessibilityIdentifier("water.dayTotal")
                        Text(waterDetail(total: total, entries: entries.count, settings: settings)).textRole(.secondary)
                    }.accessibilityElement(children: .combine)
                    if settings.water.goalMilliliters == nil { SetWaterGoalLink() }
                    WaterQuickAdds(day: day, quickIdentifier: "water.dayQuickAdd", otherIdentifier: "water.customAdd")
                }
                .frame(maxWidth: .infinity).padding(.vertical, Tokens.Space.s)
                .bareListRow()
            }
            Section(f("Drinks")) {
                if entries.isEmpty { Text(f("No water logged for this day.")).foregroundStyle(Tokens.muted) }
                ForEach(entries) { entry in
                    Button { edit = entry } label: {
                        HStack(spacing: Tokens.Space.ms) {
                            Image(systemName: "drop.fill").foregroundStyle(Tokens.dataCool).accessibilityHidden(true)
                            Text(waterAmount(entry.value, settings: settings)).textRole(.rowTitle, emphasized: true).numeric()
                            Spacer(minLength: 0)
                            Text(entry.date.formatted(date: .omitted, time: .shortened)).textRole(.secondary).numeric()
                        }.contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("water.entry")
                    .swipeActions(allowsFullSwipe: false) {
                        Button(f("Delete"), role: .destructive) { deleting = entry }
                    }
                }
            }
            Section {
                Chart {
                    ForEach(week) { item in
                        BarMark(x: .value(f("Date"), item.date, unit: .day),
                                y: .value(FastFormat.unit(.water, settings: settings), Units.display(item.milliliters, kind: .water, settings: settings)),
                                width: .ratio(0.6))
                            .cornerRadius(Tokens.Radius.button)
                            .foregroundStyle(Calendar.current.isDate(item.date, inSameDayAs: day) ? Tokens.dataCool : Tokens.dataCool.opacity(0.45))
                            .accessibilityLabel(item.date.formatted(date: .abbreviated, time: .omitted))
                            .accessibilityValue(item.entryCount == 0 ? f("No entries") : waterAmount(item.milliliters, settings: settings))
                    }
                    if let goal = settings.water.goalMilliliters {
                        RuleMark(y: .value(f("Your goal"), Units.display(goal, kind: .water, settings: settings)))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4])).foregroundStyle(Tokens.muted)
                    }
                }
                .chartXAxis { AxisMarks(values: .stride(by: .day)) { _ in AxisValueLabel(format: .dateTime.weekday(.narrow)) } }
                .chartXSelection(value: $selected)
                .onChange(of: selected) { _, value in if let value { day = value } }
                .frame(height: 160)
            } header: { Text(f("Last seven days")) } footer: { Text(f("Tap a day to see its drinks. Days follow your current time zone. Changing time zones can move a drink to another date.")) }
            Section {
                NavigationLink(f("Water trends")) { WaterTrendsScreen() }.accessibilityIdentifier("water.trends")
                NavigationLink(f("All water entries")) { MetricHistoryScreen(kind: .water) }
            }
        }.sevenScreenBackground().navigationTitle(f("Water"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink { WaterSettingsScreen() } label: { Image(systemName: "gearshape").accessibilityLabel(f("Goal, glass & reminders")) }
                        .accessibilityIdentifier("water.settings")
                }
            }
            .sheet(item: $edit) { MetricEditor(kind: .water, existing: $0) }
            .confirmationDialog(f("Delete this drink?"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                if let entry = deleting {
                    Button(f("Delete drink"), role: .destructive) {
                        Task { if await model.change({ $0.metrics.removeAll { $0.id == entry.id } }) { deleting = nil } }
                    }
                }
            }
    }

    /// ‹ Today ›, with the date picker in the middle for jumping further back.
    private var dayStepper: some View {
        let calendar = Calendar.current
        let isToday = calendar.isDateInToday(day)
        return HStack(spacing: Tokens.Space.s) {
            Button { day = calendar.date(byAdding: .day, value: -1, to: day) ?? day } label: {
                Image(systemName: "chevron.left").frame(minWidth: 44, minHeight: 44)
            }.accessibilityLabel(f("Previous day"))
            Spacer(minLength: 0)
            DatePicker(f("Day"), selection: $day, in: ...Date(), displayedComponents: .date).labelsHidden()
            Spacer(minLength: 0)
            Button { day = min(Date(), calendar.date(byAdding: .day, value: 1, to: day) ?? day) } label: {
                Image(systemName: "chevron.right").frame(minWidth: 44, minHeight: 44)
            }.disabled(isToday).accessibilityLabel(f("Next day"))
        }.buttonStyle(.borderless)
    }
}
