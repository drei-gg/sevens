import SwiftUI
import FastCore

struct MetricEditor: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var date: Date
    @State private var value = ""
    @State private var delete = false
    let kind: MetricKind
    let existing: MetricEntry?
    init(kind: MetricKind, existing: MetricEntry? = nil, initialDate: Date = Date()) {
        self.kind = kind; self.existing = existing; _date = State(initialValue: existing?.date ?? min(initialDate, Date()))
    }
    var body: some View {
        NavigationStack {
            Form {
                SaveFeedback()
                Section {
                    DatePicker(f("When"), selection: $date, in: ...Date())
                    LabeledContent(FastFormat.unit(kind, settings: model.archive.settings)) {
                        TextField(f("Value"), text: $value).accessibilityIdentifier("metric.value").keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                    }
                    if kind == .water && existing == nil {
                        HStack {
                            ForEach(model.archive.settings.useFluidOunces ? [8, 12, 16] : [150, 250, 500], id: \.self) { amount in
                                Button("\(amount)") { value = String(amount) }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                            }
                        }
                    }
                } footer: { Text(f("Log your own observations. There is no target you have to meet.")) }
                if existing != nil { Button(f("Delete measurement"), role: .destructive) { delete = true } }
            }
            .navigationTitle(FastFormat.kind(kind)).navigationBarTitleDisplayMode(.inline)
            .onAppear {
                if let existing { value = Units.display(existing.value, kind: kind, settings: model.archive.settings).formatted(.number.precision(.fractionLength(0...3)).grouping(.never)) }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(f("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(f("Save")) { Task { await save() } }.disabled(parsed == nil || model.busy).accessibilityIdentifier("metric.save") }
            }
            .confirmationDialog(f("Delete this measurement?"), isPresented: $delete, titleVisibility: .visible) {
                Button(f("Delete measurement"), role: .destructive) {
                    if let id = existing?.id { Task { if await model.change({ $0.metrics.removeAll { $0.id == id } }) { dismiss() } } }
                }
            }
        }
    }
    private var parsed: Double? {
        let formatter = NumberFormatter(); formatter.numberStyle = .decimal
        guard let number = formatter.number(from: value)?.doubleValue, number.isFinite else { return nil }
        let canonical = Units.canonical(number, kind: kind, settings: model.archive.settings)
        return kind.validRange.contains(canonical) ? canonical : nil
    }
    private func save() async {
        guard let parsed else { return }
        let entry = MetricEntry(id: existing?.id ?? UUID(), date: date, kind: kind, value: parsed, externalID: existing?.externalID)
        if await model.saveMetric(entry) { dismiss() }
    }
}

struct MetricHistoryScreen: View {
    @Environment(FastModel.self) private var model
    let kind: MetricKind
    @State private var edit: MetricEntry?
    var body: some View {
        List(model.archive.metrics.filter { $0.kind == kind }.sorted { $0.date > $1.date }) { entry in
            Button { edit = entry } label: {
                LabeledContent(entry.date.formatted(date: .abbreviated, time: .shortened)) {
                    Text(Units.display(entry.value, kind: kind, settings: model.archive.settings).formatted(.number.precision(.fractionLength(0...2)))
                        + " " + FastFormat.unit(kind, settings: model.archive.settings))
                }.foregroundStyle(.primary)
            }
        }.navigationTitle(FastFormat.kind(kind))
            .sheet(item: $edit) { MetricEditor(kind: kind, existing: $0) }
    }
}
