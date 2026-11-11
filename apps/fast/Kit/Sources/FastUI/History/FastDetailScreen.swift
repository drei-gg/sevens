import SwiftUI
import FastCore
import SevenKit

struct FastDetailScreen: View {
    @Environment(FastModel.self) private var model
    let id: UUID
    @State private var edit = false
    @State private var checkIn = false
    var body: some View {
        if let fast = model.archive.fasts.first(where: { $0.id == id }) {
            List {
                Section {
                    Text(FastFormat.duration(fast.duration(at: Date()))).font(.largeTitle).monospacedDigit()
                    LabeledContent(f("Started"), value: fast.start.formatted(date: .abbreviated, time: .shortened))
                    if let end = fast.end { LabeledContent(f("Finished"), value: end.formatted(date: .abbreviated, time: .shortened)) }
                    LabeledContent(f("Goal"), value: FastFormat.goal(fast.goalMinutes))
                    LabeledContent(f("Recorded time zone"), value: fast.timeZoneID)
                }
                if !fast.intention.isEmpty { Section(f("Intention")) { Text(fast.intention).textSelection(.enabled) } }
                if !fast.reflection.isEmpty { Section(f("Reflection")) { Text(fast.reflection).textSelection(.enabled) } }
                if !fast.tags.isEmpty { Section(f("Tags")) { Text(fast.tags.joined(separator: " · ")) } }
                Section(f("Observations")) {
                    ForEach(model.archive.journal.filter { $0.fastID == id }.sorted { $0.date < $1.date }) { entry in
                        NavigationLink { JournalDetailScreen(id: entry.id) } label: { JournalRow(entry: entry) }
                    }
                    GatedButton(feature: f("Journal"), action: { checkIn = true }) { Label(f("Add a check-in"), systemImage: "square.and.pencil") }
                }
            }
            .navigationTitle(f("Fast details"))
            .toolbar { Button(f("Edit")) { edit = true } }
            .sheet(isPresented: $edit) { FastEditor(mode: .edit(fast)) }
            .sheet(isPresented: $checkIn) { JournalEditor(fastID: id) }
        } else { ContentUnavailableView(f("Fast deleted"), systemImage: "calendar") }
    }
}
