import SwiftUI
import FastCore
import SevenKit

struct JournalScreen: View {
    @Environment(FastModel.self) private var model
    @State private var query = ""
    @State private var add = false
    var entries: [JournalEntry] {
        model.archive.journal.filter { entry in query.isEmpty || (entry.text + " " + entry.tags.joined(separator: " ")).localizedStandardContains(query) }.sorted { $0.date > $1.date }
    }
    var body: some View {
        List {
            Section {
                Text(f("A record of how it felt.")).textRole(.title)
                Text(f("Set an intention. Notice what happens. Keep what you learn.")).foregroundStyle(Tokens.muted)
            }
            if entries.isEmpty {
                ContentUnavailableView(f("Room for your observations"), systemImage: "book.closed", description: Text(f("Write during a fast, after it, or on an ordinary day. Nothing here is scored.")))
            } else {
                ForEach(entries) { entry in NavigationLink { JournalDetailScreen(id: entry.id) } label: { JournalRow(entry: entry) } }
            }
        }
        .sevenScreenBackground().navigationTitle(f("Journal"))
        .searchable(text: $query, prompt: f("Search observations & tags"))
        .toolbar { GatedButton(feature: f("Journal"), action: { add = true }) { Label(f("New journal entry"), systemImage: "square.and.pencil").labelStyle(.iconOnly) } }
        .sheet(isPresented: $add) { JournalEditor(fastID: model.archive.active?.id) }
    }
}
struct JournalRow: View {
    let entry: JournalEntry
    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Text(entry.date, format: .dateTime.month().day().hour().minute()).textRole(.caption)
            Text(entry.text.isEmpty ? f("A quiet check-in") : entry.text).lineLimit(3)
            if !entry.tags.isEmpty { Text(entry.tags.joined(separator: " · ")).textRole(.caption) }
            if entry.mood != nil || entry.energy != nil || entry.hunger != nil { Text(ratings(entry)).textRole(.caption) }
        }.padding(.vertical, Tokens.Space.xs).accessibilityElement(children: .combine)
    }
}
func ratings(_ entry: JournalEntry) -> String {
    [(f("Mood"), entry.mood), (f("Energy"), entry.energy), (f("Hunger"), entry.hunger)]
        .compactMap { name, value in value.map { rating in name + " \(rating)/5" } }.joined(separator: " · ")
}

struct JournalDetailScreen: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let id: UUID
    @State private var edit = false
    @State private var delete = false
    var body: some View {
        if let entry = model.archive.journal.first(where: { $0.id == id }) {
            List {
                Section { Text(entry.date, format: .dateTime.month().day().year().hour().minute()).foregroundStyle(Tokens.muted)
                    Text(entry.text.isEmpty ? f("A quiet check-in") : entry.text).textSelection(.enabled)
                    if !ratings(entry).isEmpty { Text(ratings(entry)).textRole(.secondary) }
                    if !entry.tags.isEmpty { Text(entry.tags.joined(separator: " · ")) }
                }
                if let fastID = entry.fastID { NavigationLink(f("Linked fast")) { FastDetailScreen(id: fastID) } }
                Button(f("Delete entry"), role: .destructive) { delete = true }
            }
            .sevenScreenBackground().navigationTitle(f("Observation"))
            .toolbar { Button(f("Edit")) { edit = true } }
            .sheet(isPresented: $edit) { JournalEditor(entry: entry) }
            .confirmationDialog(f("Delete this journal entry?"), isPresented: $delete, titleVisibility: .visible) {
                Button(f("Delete entry"), role: .destructive) { Task { if await model.change({ $0.journal.removeAll { $0.id == id } }) { dismiss() } } }
            }
        } else { ContentUnavailableView(f("Entry deleted"), systemImage: "book.closed") }
    }
}
