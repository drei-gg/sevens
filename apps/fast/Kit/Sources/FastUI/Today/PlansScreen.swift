import SwiftUI
import FastCore
import SevenKit

struct PlansScreen: View {
    @Environment(FastModel.self) private var model
    @State private var addPlan = false
    @State private var editPlan: PlannedFast?
    @State private var addPreset = false
    @State private var editPreset: FastPreset?
    @State private var startPlan: PlannedFast?
    @State private var removePreset: FastPreset?
    var body: some View {
        List {
            Section {
                Text(f("Plans are reminders, not records. Nothing starts automatically.")).foregroundStyle(Tokens.muted)
                GatedButton(feature: f("Plans"), action: { addPlan = true }) { Label(f("Plan a fast"), systemImage: "calendar.badge.plus") }
            }
            Section(f("Planned fasts")) {
                if model.archive.plans.isEmpty { Text(f("Leave room for real life. Add dates that work for you.")).foregroundStyle(Tokens.muted) }
                ForEach(model.archive.plans.sorted { $0.start < $1.start }) { plan in
                    VStack(alignment: .leading, spacing: Tokens.Space.s) {
                        Button { editPlan = plan } label: {
                            VStack(alignment: .leading) {
                                Text(plan.start, format: .dateTime.month().day().hour().minute()).textRole(.rowTitle, emphasized: true)
                                Text(FastFormat.goal(plan.goalMinutes)).foregroundStyle(Tokens.muted)
                                if !plan.intention.isEmpty { Text(plan.intention).textRole(.secondary) }
                            }.foregroundStyle(.primary)
                        }
                        if model.archive.active == nil {
                            GatedButton(feature: f("Start a fast"), action: { startPlan = plan }) { Text(f("Start now from this plan")) }
                        }
                    }
                }
            }
            Section(f("Your presets")) {
                ForEach(model.archive.presets) { preset in
                    Button { editPreset = preset } label: {
                        LabeledContent(preset.name, value: FastFormat.goal(preset.goalMinutes)).foregroundStyle(.primary)
                    }.swipeActions { Button(f("Delete"), role: .destructive) { removePreset = preset } }
                }
                GatedButton(feature: f("Presets"), action: { addPreset = true }) { Label(f("Save a custom preset"), systemImage: "plus") }
            }
            NavigationLink(f("Reminder settings")) { ReminderSettingsScreen() }
        }
        .sevenScreenBackground().navigationTitle(f("Plans & presets"))
        .sheet(isPresented: $addPlan) { PlanEditor() }
        .sheet(item: $editPlan) { PlanEditor(existing: $0) }
        .sheet(isPresented: $addPreset) { PresetEditor() }
        .sheet(item: $editPreset) { PresetEditor(existing: $0) }
        .confirmationDialog(f("Start this plan now?"), isPresented: Binding(get: { startPlan != nil }, set: { if !$0 { startPlan = nil } }), titleVisibility: .visible) {
            Button(f("Start now")) { if let plan = startPlan { Task { await start(plan) } } }
        } message: { Text(f("The actual start will be now. You can correct it later.")) }
        .confirmationDialog(f("Delete this preset?"), isPresented: Binding(get: { removePreset != nil }, set: { if !$0 { removePreset = nil } }), titleVisibility: .visible) {
            Button(f("Delete"), role: .destructive) { if let id = removePreset?.id { Task { _ = await model.change { $0.presets.removeAll { $0.id == id } }; removePreset = nil } } }
        }
    }
    private func start(_ plan: PlannedFast) async {
        guard model.store.paidFeaturesAvailable else { return }
        let now = Date()
        let fast = FastRecord(start: now, goalMinutes: plan.goalMinutes, eatingMinutes: plan.eatingMinutes, intention: plan.intention)
        _ = await model.change { archive in try archive.save(fast, now: now); archive.plans.removeAll { $0.id == plan.id } }
        startPlan = nil
    }
}

struct PlanEditor: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var plan: PlannedFast
    @State private var hours: Double
    @State private var eating: Double
    @State private var openEnded: Bool
    @State private var delete = false
    let existing: PlannedFast?
    init(existing: PlannedFast? = nil) {
        self.existing = existing
        let value = existing ?? PlannedFast(start: Date().addingTimeInterval(86_400))
        _plan = State(initialValue: value); _hours = State(initialValue: Double(value.goalMinutes ?? 960)/60)
        _eating = State(initialValue: Double(value.eatingMinutes)/60); _openEnded = State(initialValue: value.goalMinutes == nil)
    }
    var body: some View {
        NavigationStack {
            Form {
                SaveFeedback()
                DatePicker(f("Planned start"), selection: $plan.start)
                Toggle(f("Open ended"), isOn: $openEnded)
                if !openEnded { TextField(f("Goal hours"), value: $hours, format: .number).keyboardType(.decimalPad) }
                TextField(f("Eating window hours"), value: $eating, format: .number).keyboardType(.decimalPad)
                TextField(f("Intention"), text: $plan.intention, axis: .vertical)
                if existing != nil { Button(f("Delete plan"), role: .destructive) { delete = true } }
            }.sevenScreenBackground().navigationTitle(f("Plan a fast")).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(f("Cancel")) { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button(f("Save")) { Task { await save() } }.disabled(model.busy || !valid) }
                }
                .confirmationDialog(f("Delete this plan?"), isPresented: $delete, titleVisibility: .visible) {
                    Button(f("Delete plan"), role: .destructive) { let id = plan.id; Task { if await model.change({ $0.plans.removeAll { $0.id == id } }) { dismiss() } } }
                }
        }
    }
    private var valid: Bool { hours.isFinite && eating.isFinite && (openEnded || (1.0/60...8760).contains(hours)) && (1.0/60...168).contains(eating) }
    private func save() async {
        guard model.allowNewRecord(existing: existing != nil) else { return }
        plan.goalMinutes = openEnded ? nil : Int((hours * 60).rounded()); plan.eatingMinutes = Int((eating * 60).rounded())
        let value = plan
        if await model.change({ $0.plans.removeAll { $0.id == value.id }; $0.plans.append(value) }) { dismiss() }
    }
}

struct PresetEditor: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var hours = 16.0
    @State private var eating = 8.0
    @State private var openEnded = false
    let existing: FastPreset?
    init(existing: FastPreset? = nil) {
        self.existing = existing
        _name = State(initialValue: existing?.name ?? "")
        _hours = State(initialValue: Double(existing?.goalMinutes ?? 960) / 60)
        _eating = State(initialValue: Double(existing?.eatingMinutes ?? 480) / 60)
        _openEnded = State(initialValue: existing != nil && existing?.goalMinutes == nil)
    }
    var body: some View {
        NavigationStack {
            Form {
                SaveFeedback()
                TextField(f("Preset name"), text: $name)
                Toggle(f("Open ended"), isOn: $openEnded)
                if !openEnded { TextField(f("Goal hours"), value: $hours, format: .number).keyboardType(.decimalPad) }
                TextField(f("Eating window hours"), value: $eating, format: .number).keyboardType(.decimalPad)
            }.sevenScreenBackground().navigationTitle(f("Custom preset")).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(f("Cancel")) { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button(f("Save")) {
                        guard model.allowNewRecord(existing: existing != nil) else { return }
                        let preset = FastPreset(id: existing?.id ?? UUID(), name: name, goalMinutes: openEnded ? nil : Int((hours * 60).rounded()), eatingMinutes: Int((eating * 60).rounded()))
                        Task { if await model.change({ archive in archive.presets.removeAll { $0.id == preset.id }; archive.presets.append(preset) }) { dismiss() } }
                    }.disabled(model.busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !hours.isFinite || !eating.isFinite || (!openEnded && !(1.0/60...8760).contains(hours)) || !(1.0/60...168).contains(eating)) }
                }
        }
    }
}
