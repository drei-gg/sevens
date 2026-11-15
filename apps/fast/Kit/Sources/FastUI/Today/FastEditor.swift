import SwiftUI
import FastCore
import SevenKit

enum FastEditorMode {
    case start, past(Date), edit(FastRecord), finish(FastRecord)
}

struct FastEditor: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var record: FastRecord
    @State private var hasGoal: Bool
    @State private var goalHours: Double
    @State private var eatingHours: Double
    @State private var tags: String
    @State private var confirmDelete = false
    private let mode: FastEditorMode
    private let isExisting: Bool
    private let isFinishing: Bool
    private let isCompleted: Bool

    init(mode: FastEditorMode) {
        self.mode = mode
        let fast: FastRecord
        switch mode {
        case .start:
            fast = FastRecord(start: Date()); isExisting = false; isFinishing = false; isCompleted = false
        case .past(let date):
            let end = min(Date(), Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: date) ?? date)
            fast = FastRecord(start: end.addingTimeInterval(-16 * 3_600), end: end)
            isExisting = false; isFinishing = false; isCompleted = true
        case .edit(let value):
            fast = value; isExisting = true; isFinishing = false; isCompleted = value.end != nil
        case .finish(var value):
            value.end = Date(); fast = value; isExisting = true; isFinishing = true; isCompleted = true
        }
        _record = State(initialValue: fast); _hasGoal = State(initialValue: fast.goalMinutes != nil)
        _goalHours = State(initialValue: Double(fast.goalMinutes ?? 960) / 60)
        _eatingHours = State(initialValue: Double(fast.eatingMinutes) / 60)
        _tags = State(initialValue: fast.tags.joined(separator: ", "))
    }
    var body: some View {
        NavigationStack {
            Form {
                SaveFeedback()
                if !isExisting {
                    Section(f("Choose a rhythm")) {
                        ScrollView(.horizontal) {
                            HStack {
                                ForEach(FastPreset.defaults + model.archive.presets) { preset in
                                    Button(preset.name) { apply(preset) }.buttonStyle(.bordered)
                                }
                            }
                        }
                    }
                }
                Section(f("Times")) {
                    DatePicker(f("Started"), selection: $record.start, in: ...Date())
                    if isCompleted {
                        DatePicker(f("Finished"), selection: Binding(get: { record.end ?? Date() }, set: { record.end = $0 }), in: ...Date())
                        if let end = record.end { LabeledContent(f("Duration"), value: FastFormat.duration(end.timeIntervalSince(record.start))) }
                    }
                    Toggle(f("Set a goal"), isOn: $hasGoal)
                    if hasGoal { LabeledContent(f("Goal hours")) { TextField(f("Hours"), value: $goalHours, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(minWidth: 70) } }
                    LabeledContent(f("Eating window hours")) { TextField(f("Hours"), value: $eatingHours, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(minWidth: 70) }
                }
                Section(f("Intention")) { TextField(f("What would you like to make room for?"), text: $record.intention, axis: .vertical).lineLimit(2...5) }
                if isCompleted {
                    Section(f("Reflection")) { TextField(f("What did you notice? What would you change?"), text: $record.reflection, axis: .vertical).lineLimit(3...8) }
                }
                Section { TextField(f("Tags, separated by commas"), text: $tags) }
                Section {
                    Text(f("An early finish is still a record. This timer does not measure ketosis, autophagy or fat burning.")).textRole(.caption)
                }
                if isExisting {
                    Section { Button(isCompleted && !isFinishing ? f("Delete fast") : f("Discard fast"), role: .destructive) { confirmDelete = true } }
                }
            }
            .onAppear { model.message = nil }
            .sevenScreenBackground().navigationTitle(isFinishing ? f("End fast") : isExisting ? f("Edit fast") : isCompleted ? f("Add past fast") : f("Start a fast"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(f("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(f("Save")) { Task { await save() } }.disabled(model.busy || !validNumbers).accessibilityIdentifier("fast.save")
                }
            }
            .confirmationDialog(f("Delete this fast? Journal entries will be kept."), isPresented: $confirmDelete, titleVisibility: .visible) {
                Button(f("Delete fast"), role: .destructive) { let id = record.id; Task { if await model.change({ $0.deleteFast(id) }) { dismiss() } } }
            }
        }
    }
    private var validNumbers: Bool { goalHours.isFinite && eatingHours.isFinite && (!hasGoal || (1.0/60...8_760).contains(goalHours)) && (1.0/60...168).contains(eatingHours) }
    private func apply(_ preset: FastPreset) { hasGoal = preset.goalMinutes != nil; goalHours = Double(preset.goalMinutes ?? 960)/60; eatingHours = Double(preset.eatingMinutes)/60 }
    private func save() async {
        record.goalMinutes = hasGoal ? Int32((goalHours * 60).rounded()) : nil
        record.eatingMinutes = Int32((eatingHours * 60).rounded()); record.tags = FastFormat.tags(tags)
        // Start time is chosen explicitly, including backdating; never reset it during save.
        if await model.saveFast(record) { dismiss() }
    }
}
