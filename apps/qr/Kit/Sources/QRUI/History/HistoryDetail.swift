import SwiftUI
import QRCore
import SevenKit

/// One saved code: the verdict it got at the time, the payload, when and how, a note, and a
/// "Check again" that runs today's checks. Notes and delete work in every state (never gated).
public struct HistoryDetail: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let item: HistoryItem

    @State private var draft: String
    @State private var checking = false
    @State private var fresh: FreshAnalysis?
    @State private var confirmDelete = false
    @State private var noteSaveFailed = false

    public init(item: HistoryItem) {
        self.item = item
        _draft = State(initialValue: item.note ?? "")
    }

    /// The live copy (a note saved here updates it), else the one we were given.
    private var current: HistoryItem { model.historyItems.first { $0.id == item.id } ?? item }

    private struct FreshAnalysis: Identifiable {
        let id = UUID()
        let analysis: Analysis
    }

    public var body: some View {
        let shown = current
        List {
            Section { verdictHeader(shown) }
                .listRowBackground(Tokens.card)
            Section {
                Text(shown.payload)
                    .font(.body.monospaced())
                    .foregroundStyle(Tokens.ink)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } header: {
                Text(shown.payloadEncoding == .base64
                     ? String(localized: "history.detail.payload.binary", defaultValue: "Contents (binary, shown as base64)", bundle: .module)
                     : String(localized: "history.detail.payload", defaultValue: "Contents", bundle: .module))
                    .foregroundStyle(Tokens.muted)
            }
            .listRowBackground(Tokens.card)
            Section {
                LabeledContent(String(localized: "history.detail.kind", defaultValue: "How", bundle: .module)) {
                    Text(HistoryLabels.kind(shown.kind))
                }
                LabeledContent(String(localized: "history.detail.symbology", defaultValue: "Code type", bundle: .module)) {
                    Text(HistoryLabels.symbology(shown.symbology))
                }
                LabeledContent(String(localized: "history.detail.date", defaultValue: "Date", bundle: .module)) {
                    Text(shown.date.formatted(date: .abbreviated, time: .shortened))
                }
            }
            .listRowBackground(Tokens.card)
            Section {
                Button {
                    checkAgain(shown)
                } label: {
                    Label(String(localized: "history.detail.checkAgain", defaultValue: "Check again", bundle: .module),
                          systemImage: "arrow.clockwise")
                }
                .disabled(checking)
            } footer: {
                Text(String(localized: "history.detail.checkAgain.footer",
                            defaultValue: "Runs today's checks on this code. The verdict above is the one it got when it was saved.",
                            bundle: .module))
                    .foregroundStyle(Tokens.muted)
            }
            .listRowBackground(Tokens.card)
            noteSection
            Section {
                Button(role: .destructive) {
                    confirmDelete = true
                } label: {
                    Label(String(localized: "history.detail.delete", defaultValue: "Delete from history", bundle: .module),
                          systemImage: "trash")
                }
            }
            .listRowBackground(Tokens.card)
        }
        .scrollContentBackground(.hidden)
        .background(Tokens.paper.ignoresSafeArea())
        .navigationTitle(VerdictText.typeName(for: shown.typeID))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $fresh) { fresh in ResultSheet(analysis: fresh.analysis) }
        .confirmationDialog(
            String(localized: "history.detail.delete.title", defaultValue: "Delete this item?", bundle: .module),
            isPresented: $confirmDelete, titleVisibility: .visible
        ) {
            Button(String(localized: "history.delete", defaultValue: "Delete", bundle: .module), role: .destructive) {
                Task {
                    await model.deleteHistory(ids: [item.id])
                    dismiss()
                }
            }
            Button(String(localized: "history.cancel", defaultValue: "Cancel", bundle: .module), role: .cancel) {}
        }
        .alert(String(localized: "history.detail.note.failed", defaultValue: "The note wasn't saved.", bundle: .module),
               isPresented: $noteSaveFailed) {
            Button(String(localized: "history.ok", defaultValue: "OK", bundle: .module), role: .cancel) {}
        }
    }

    // MARK: Pieces

    private func verdictHeader(_ shown: HistoryItem) -> some View {
        let style = VerdictText.style(for: shown.level)
        let typeName = VerdictText.typeName(for: shown.typeID)
        let signals = HistoryVerdictSignals.signals(from: shown.signalIDs)
        let reasons = SignalText.sentences(for: signals.filter { $0.severity != .info })
        return VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Label {
                Text(VerdictText.title(for: shown.level)).font(.title2.weight(.semibold))
            } icon: {
                Image(systemName: style.symbolName)
            }
            .foregroundStyle(style.color)
            Text(typeName).font(.headline).foregroundStyle(Tokens.ink)
            ForEach(reasons, id: \.self) { reason in
                Text(reason).font(.callout).foregroundStyle(Tokens.ink)
            }
            Text(String(localized: "history.detail.atTheTime", defaultValue: "The verdict when it was saved", bundle: .module))
                .font(.footnote).foregroundStyle(Tokens.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(VerdictText.accessibilitySentence(level: shown.level, typeName: typeName, signals: signals))
    }

    private var noteSection: some View {
        Section {
            TextEditor(text: $draft)
                .font(.body)
                .frame(minHeight: 120)
                .scrollContentBackground(.hidden)
                .accessibilityLabel(String(localized: "history.detail.note", defaultValue: "Note", bundle: .module))
                .onChange(of: draft) { _, value in
                    if value.count > HistoryStore.maxNoteLength { draft = String(value.prefix(HistoryStore.maxNoteLength)) }
                }
            Button(String(localized: "history.detail.note.save", defaultValue: "Save note", bundle: .module)) {
                Task {
                    let saved = await model.setNote(draft, for: item.id)
                    if !saved { noteSaveFailed = true }
                }
            }
            .disabled(!HistoryNote.isChanged(draft: draft, saved: current.note))
        } header: {
            Text(String(localized: "history.detail.note", defaultValue: "Note", bundle: .module))
                .foregroundStyle(Tokens.muted)
        }
        .listRowBackground(Tokens.card)
    }

    private func checkAgain(_ shown: HistoryItem) {
        checking = true
        Task {
            let result = await model.analysis(of: shown.scannedCode)
            checking = false
            fresh = FreshAnalysis(analysis: result)
        }
    }
}

#Preview("History detail, light") {
    NavigationStack { HistoryDetail(item: AppModel.sampleHistory[1]) }.qrStore(.preview(state: .trial))
}
#Preview("History detail, dark, locked") {
    NavigationStack { HistoryDetail(item: AppModel.sampleHistory[0]) }
        .qrStore(.preview(state: .expired)).preferredColorScheme(.dark)
}
#Preview("History detail, largest text") {
    NavigationStack { HistoryDetail(item: AppModel.sampleHistory[1]) }.qrStore(.preview(state: .trial))
        .environment(\.dynamicTypeSize, .accessibility5)
}
