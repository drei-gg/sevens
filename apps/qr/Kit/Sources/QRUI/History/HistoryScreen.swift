import SwiftUI
import QRCore
import SevenKit

/// The History list (SPEC §10). Free to read: search, notes, export and delete work in every
/// state; only saving new scans needs the trial or the unlock.
public struct HistoryScreen: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var results: [HistoryItem]?
    @State private var selection = Set<UUID>()
    @State private var editMode: EditMode = .inactive
    @State private var exportFile: HistoryExportFile?
    @State private var exportFailed = false
    @State private var exportedURL: URL?
    @State private var confirmDeleteSelection = false
    @State private var showUnlock = false

    /// The History list (free to read, SPEC §10). Pushed inside a NavigationStack.
    public init() {}

    private var emptyState: HistoryEmptyState {
        HistoryEmptyState.resolve(itemCount: model.historyCount, keepHistory: model.keepsHistory,
                                  locked: model.historyIsLocked, readOnly: model.historyIsReadOnly)
    }

    private var isSearching: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var shown: [HistoryItem] { isSearching ? (results ?? []) : model.historyItems }

    public var body: some View {
        Group {
            switch emptyState {
            case .list: list
            case .readOnly: readOnlyView
            case .historyOff: historyOffView
            case .locked: lockedView
            case .noItems: noItemsView
            }
        }
        .navigationTitle(String(localized: "history.title", defaultValue: "History", bundle: .module))
        .environment(\.editMode, $editMode)
        .toolbar {
            if emptyState == .list {
                ToolbarItem(placement: .topBarTrailing) { exportMenu }
                ToolbarItem(placement: .topBarTrailing) { EditButton() }
            }
        }
        .sheet(item: $exportFile, onDismiss: removeExportFile) { file in HistoryExportSheet(file: file) }
        .alert(String(localized: "history.export.failed", defaultValue: "The export didn't work. Try again.", bundle: .module),
               isPresented: $exportFailed) {
            Button(String(localized: "history.ok", defaultValue: "OK", bundle: .module), role: .cancel) {}
        }
        .unlockSheet(isPresented: $showUnlock)
        .onChange(of: model.historyCount) { _, count in
            if count == 0 { editMode = .inactive; selection = [] }
        }
    }

    // MARK: List

    private var list: some View {
        List(selection: $selection) {
            if let reason = model.historyReadOnlyReason, model.historyIsReadOnly {
                Section { Text(reason).font(.callout).foregroundStyle(Tokens.muted) }
            }
            if HistoryEmptyState.showsLockedNote(itemCount: model.historyCount, keepHistory: model.keepsHistory,
                                                 locked: model.historyIsLocked, readOnly: model.historyIsReadOnly) {
                Section { lockedNote }
            }
            Section {
                ForEach(shown) { item in
                    NavigationLink {
                        HistoryDetail(item: item)
                    } label: {
                        HistoryRow(item: item)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            delete([item.id])
                        } label: {
                            Label(String(localized: "history.delete", defaultValue: "Delete", bundle: .module),
                                  systemImage: "trash")
                        }
                    }
                }
            }
        }
        .sevenScreenBackground()
        .listStyle(.insetGrouped)
        .accessibilityIdentifier(QRAccessibilityID.historyList)
        .overlay {
            if isSearching && results?.isEmpty == true { ContentUnavailableView.search(text: query) }
        }
        .searchable(text: $query, prompt: Text(String(localized: "history.search.prompt",
                                                       defaultValue: "Search history", bundle: .module)))
        .accessibilityIdentifier(QRAccessibilityID.historySearch)
        .task(id: SearchTrigger(query: query, items: model.historyItems)) {
            guard isSearching else { results = nil; return }
            let found = await model.searchHistory(query)
            if !Task.isCancelled { results = found }
        }
        .toolbar {
            if editMode.isEditing {
                ToolbarItem(placement: .bottomBar) {
                    Button(role: .destructive) {
                        confirmDeleteSelection = true
                    } label: {
                        Text(String(localized: "history.deleteSelected", defaultValue: "Delete selected", bundle: .module))
                    }
                    .disabled(selection.isEmpty)
                }
            }
        }
        .confirmationDialog(
            String(localized: "history.deleteSelected.title", defaultValue: "Delete the selected items?", bundle: .module),
            isPresented: $confirmDeleteSelection, titleVisibility: .visible
        ) {
            Button(String(localized: "history.delete", defaultValue: "Delete", bundle: .module), role: .destructive) {
                delete(selection)
                selection = []
                editMode = .inactive
            }
            Button(String(localized: "history.cancel", defaultValue: "Cancel", bundle: .module), role: .cancel) {}
        } message: {
            Text(String(localized: "history.deleteSelected.message", defaultValue: "This can't be undone.", bundle: .module))
        }
    }

    private struct SearchTrigger: Equatable {
        let query: String
        let items: [HistoryItem]
    }

    private var lockedNote: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Text(HistoryScreen.lockedText).font(.callout).foregroundStyle(.primary)
            unlockButton
        }
    }

    // MARK: Empty states

    static var lockedText: String {
        String(localized: "history.locked", defaultValue: "Your history stays here. Saving new scans needs the unlock.",
               bundle: .module)
    }

    private var unlockButton: some View {
        Button(String(localized: "history.unlock", defaultValue: "Unlock", bundle: .module)) { showUnlock = true }
            .buttonStyle(.bordered)
    }

    private var noItemsView: some View {
        ContentUnavailableView {
            Label(String(localized: "history.empty.title", defaultValue: "Nothing here yet", bundle: .module),
                  systemImage: "clock")
        } description: {
            Text(String(localized: "history.empty.body",
                        defaultValue: "Codes you scan or make will show up here.", bundle: .module))
        }
        .accessibilityIdentifier(QRAccessibilityID.historyList)
    }

    private var historyOffView: some View {
        ContentUnavailableView {
            Label(String(localized: "history.off.title", defaultValue: "Keep history is off", bundle: .module),
                  systemImage: "clock.badge.xmark")
        } description: {
            Text(String(localized: "history.off.body",
                        defaultValue: "Nothing you scan or make is being saved.", bundle: .module))
        } actions: {
            NavigationLink {
                SettingsScreen()
            } label: {
                Text(String(localized: "history.off.settings", defaultValue: "Open Settings", bundle: .module))
            }
            .buttonStyle(.bordered)
        }
        .accessibilityIdentifier(QRAccessibilityID.historyList)
    }

    private var lockedView: some View {
        ContentUnavailableView {
            Label(String(localized: "history.locked.title", defaultValue: "Nothing saved", bundle: .module),
                  systemImage: "lock")
        } description: {
            Text(HistoryScreen.lockedText)
        } actions: {
            unlockButton
        }
        .accessibilityIdentifier(QRAccessibilityID.historyList)
    }

    private var readOnlyView: some View {
        ContentUnavailableView {
            Label(String(localized: "history.readOnly.title", defaultValue: "History can't be read", bundle: .module),
                  systemImage: "exclamationmark.triangle")
        } description: {
            Text(model.historyReadOnlyReason ?? String(
                localized: "history.readOnly.fallback",
                defaultValue: "This version can't read the saved history, so it is left as it is.", bundle: .module))
        }
        .accessibilityIdentifier(QRAccessibilityID.historyList)
    }

    // MARK: Actions

    private var exportMenu: some View {
        Menu {
            Button(String(localized: "history.export.json", defaultValue: "Export as JSON", bundle: .module)) {
                export(.json)
            }
            Button(String(localized: "history.export.csv", defaultValue: "Export as CSV", bundle: .module)) {
                export(.csv)
            }
        } label: {
            Label(String(localized: "history.export", defaultValue: "Export", bundle: .module),
                  systemImage: "square.and.arrow.up")
        }
        .accessibilityLabel(String(localized: "history.export", defaultValue: "Export", bundle: .module))
    }

    private func delete(_ ids: Set<UUID>) {
        Task { await model.deleteHistory(ids: ids) }
    }

    private func export(_ format: HistoryExportFiles.Format) {
        Task {
            do {
                let data: Data
                switch format {
                case .json: data = try await model.exportHistoryJSON()
                case .csv: data = Data(await model.exportHistoryCSV().utf8)
                }
                let name = HistoryExportFiles.fileName(format: format, date: Date())
                let url = try HistoryExportFiles.write(data, named: name)
                exportedURL = url
                exportFile = HistoryExportFile(url: url)
            } catch {
                exportFailed = true
            }
        }
    }

    private func removeExportFile() {
        // The sheet is gone: don't leave a copy of the history in the temporary folder.
        if let exportedURL { try? FileManager.default.removeItem(at: exportedURL) }
        exportedURL = nil
    }
}

#Preview("History, light") {
    NavigationStack { HistoryScreen() }.qrStore(.preview(state: .trial))
}
#Preview("History, dark") {
    NavigationStack { HistoryScreen() }.qrStore(.preview(state: .unlocked)).preferredColorScheme(.dark)
}
#Preview("History, largest text") {
    NavigationStack { HistoryScreen() }.qrStore(.preview(state: .trial))
        .environment(\.dynamicTypeSize, .accessibility5)
}
#Preview("History, locked and empty") {
    NavigationStack { HistoryScreen() }.qrStore(.preview(state: .expired, history: []))
}
#Preview("History, empty") {
    NavigationStack { HistoryScreen() }.qrStore(.preview(state: .trial, history: []))
}
