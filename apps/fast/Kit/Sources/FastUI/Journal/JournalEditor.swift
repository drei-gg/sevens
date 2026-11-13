import SwiftUI
import FastCore

struct JournalEditor: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var entry: JournalEntry
    @State private var tags: String
    init(entry: JournalEntry? = nil, date: Date = Date(), fastID: UUID? = nil) {
        let value = entry ?? JournalEntry(date: date, fastID: fastID)
        _entry = State(initialValue: value); _tags = State(initialValue: value.tags.joined(separator: ", "))
    }
    var body: some View {
        NavigationStack {
            Form {
                SaveFeedback()
                Section {
                    DatePicker(f("When"), selection: $entry.date, in: ...Date())
                    Picker(f("Linked fast"), selection: $entry.fastID) {
                        Text(f("An ordinary day")).tag(nil as UUID?)
                        ForEach(model.archive.fasts.sorted { $0.start > $1.start }) { fast in
                            Text(fast.start.formatted(date: .abbreviated, time: .shortened)).tag(Optional(fast.id))
                        }
                    }
                }
                Section(f("Observation")) {
                    TextField(f("What’s happening in your body, your day, or your thoughts?"), text: $entry.text, axis: .vertical)
                        .lineLimit(6...16).accessibilityIdentifier("journal.text")
                }
                Section {
                    RatingPicker(title: f("Mood"), low: f("Low"), high: f("Good"), value: $entry.mood)
                    RatingPicker(title: f("Energy"), low: f("Low"), high: f("High"), value: $entry.energy)
                    RatingPicker(title: f("Hunger"), low: f("None"), high: f("Strong"), value: $entry.hunger)
                } header: { Text(f("Optional check-in")) } footer: { Text(f("These are your observations, not a score to improve.")) }
                Section { TextField(f("Tags, separated by commas"), text: $tags) }
            }
            .onAppear { model.message = nil }
            .sevenScreenBackground().navigationTitle(f("Check in")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(f("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(f("Save")) { entry.tags = FastFormat.tags(tags); Task { if await model.saveJournal(entry) { dismiss() } } }
                        .disabled(model.busy || (entry.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && entry.mood == nil && entry.energy == nil && entry.hunger == nil))
                        .accessibilityIdentifier("journal.save")
                }
            }
        }
    }
}
struct RatingPicker: View {
    let title: String
    let low: String
    let high: String
    @Binding var value: Int?
    var body: some View {
        Picker(title, selection: $value) {
            Text(f("Not recorded")).tag(nil as Int?)
            ForEach(1...5, id: \.self) { number in Text("\(number)" + (number == 1 ? " · " + low : number == 5 ? " · " + high : "")).tag(Optional(number)) }
        }
    }
}
