import SwiftUI
import FastCore
import SevenKit

func waterAmount(_ milliliters: Double, settings: TrackerSettings) -> String {
    Units.display(milliliters, kind: .water, settings: settings).formatted(.number.precision(.fractionLength(0...1)))
        + " " + FastFormat.unit(.water, settings: settings)
}

struct WaterCard: View {
    @Environment(FastModel.self) private var model
    @State private var lastAdded: UUID?
    var body: some View {
        GroupBox(f("Water today")) {
            VStack(alignment: .leading, spacing: 14) {
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    let total = WaterHistory.total(model.archive.metrics, on: context.date)
                    Text(waterAmount(total, settings: model.archive.settings)).font(.title2.monospacedDigit())
                        .accessibilityIdentifier("water.todayTotal")
                    if let goal = model.archive.settings.water.goalMilliliters {
                        ProgressView(value: min(total, goal), total: goal)
                            .accessibilityLabel(f("Water progress"))
                        Text(f("Your goal:") + " " + waterAmount(goal, settings: model.archive.settings))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                GatedButton(feature: f("Water"), action: { Task { await add() } }) {
                    Label(f("Add") + " " + waterAmount(model.archive.settings.water.quickAddMilliliters, settings: model.archive.settings), systemImage: "plus")
                }.buttonStyle(.bordered).disabled(model.busy).accessibilityIdentifier("water.quickAdd")
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
                        Text(f("Paused until you resume.")).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                NavigationLink { WaterScreen() } label: { Label(f("Water history & settings"), systemImage: "drop") }
                    .accessibilityIdentifier("water.open")
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    private func add() async {
        let entry = MetricEntry(date: Date(), kind: .water, value: model.archive.settings.water.quickAddMilliliters)
        if await model.saveMetric(entry) { lastAdded = entry.id }
    }
}

struct WaterScreen: View {
    @Environment(FastModel.self) private var model
    @State private var day = Date()
    @State private var edit: MetricEntry?
    @State private var adding = false
    @State private var deleting: MetricEntry?
    var body: some View {
        List {
            Section {
                DatePicker(f("Day"), selection: $day, in: ...Date(), displayedComponents: .date)
                LabeledContent(f("Total"), value: waterAmount(WaterHistory.total(model.archive.metrics, on: day), settings: model.archive.settings))
                    .accessibilityIdentifier("water.dayTotal")
                GatedButton(feature: f("Water"), action: { adding = true }) { Label(f("Log a drink"), systemImage: "plus") }
                    .accessibilityIdentifier("water.customAdd")
                NavigationLink(f("Goal, glass & reminders")) { WaterSettingsScreen() }.accessibilityIdentifier("water.settings")
            } footer: { Text(f("Days follow your current time zone. Changing time zones can move a drink to another date.")) }
            Section(f("Drinks")) {
                let entries = WaterHistory.entries(model.archive.metrics, on: day)
                if entries.isEmpty { Text(f("No water logged for this day.")).foregroundStyle(.secondary) }
                ForEach(entries) { entry in
                    Button { edit = entry } label: {
                        LabeledContent(entry.date.formatted(date: .omitted, time: .shortened), value: waterAmount(entry.value, settings: model.archive.settings))
                            .foregroundStyle(.primary)
                    }.accessibilityIdentifier("water.entry")
                    .swipeActions(allowsFullSwipe: false) {
                        Button(f("Delete"), role: .destructive) { deleting = entry }
                    }
                }
            }
            Section(f("Last seven days")) {
                ForEach(0..<7, id: \.self) { offset in
                    if let date = Calendar.current.date(byAdding: .day, value: -offset, to: Date()) {
                        Button { day = date } label: {
                            LabeledContent(date.formatted(date: .abbreviated, time: .omitted), value: waterAmount(WaterHistory.total(model.archive.metrics, on: date), settings: model.archive.settings))
                                .foregroundStyle(.primary)
                        }
                    }
                }
            }
            NavigationLink(f("Water trends")) { WaterTrendsScreen() }.accessibilityIdentifier("water.trends")
            NavigationLink(f("All water entries")) { MetricHistoryScreen(kind: .water) }
        }.navigationTitle(f("Water"))
            .sheet(isPresented: $adding) { MetricEditor(kind: .water, initialDate: day) }
            .sheet(item: $edit) { MetricEditor(kind: .water, existing: $0) }
            .confirmationDialog(f("Delete this drink?"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                if let entry = deleting {
                    Button(f("Delete drink"), role: .destructive) {
                        Task { if await model.change({ $0.metrics.removeAll { $0.id == entry.id } }) { deleting = nil } }
                    }
                }
            }
    }
}
