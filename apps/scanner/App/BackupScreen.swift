import SwiftUI
import SevenKit
import UniformTypeIdentifiers

/// Backup: what one file will hold (tiles), one large Export button, Restore beneath it, and the
/// limits in plain words.
struct BackupScreen: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var exported: ShareFile?
    @State private var importing = false
    @State private var candidate: URL?
    @State private var working = false
    @State private var message: String?
    @State private var failed = false
    private var pages: Int { (model.documents + model.deletedDocuments).reduce(0) { $0 + $1.pages.count } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.l) {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.ms), count: textSize.isAccessibilitySize ? 1 : 2),
                          spacing: Tokens.Space.ms) {
                    StatTile(title: "Documents", value: model.documents.count.formatted())
                    StatTile(title: "Pages", value: pages.formatted())
                    StatTile(title: "Favorites", value: model.documents.filter { $0.favorite == true }.count.formatted())
                    StatTile(title: "Recently Deleted", value: model.deletedDocuments.count.formatted())
                }
                Text("One portable file holds your documents, original pages, adjustments, notes and Recently Deleted. Save it on this iPhone, an external drive, or a provider you choose in Files.")
                    .textRole(.body)
                VStack(spacing: Tokens.Space.ms) {
                    Button { Task { await export() } } label: {
                        Label(working ? "Working…" : "Export backup", systemImage: "externaldrive.badge.plus").frame(maxWidth: .infinity)
                    }
                    .heroAction().accessibilityIdentifier("backup.export")
                    Button { importing = true } label: {
                        Label("Restore from backup", systemImage: "arrow.counterclockwise").frame(maxWidth: .infinity, minHeight: 28)
                    }
                    .quickAddChip().accessibilityIdentifier("backup.restore")
                }
                if let message {
                    StatusLine(text: message, systemImage: failed ? "exclamationmark.triangle.fill" : "checkmark.circle.fill", color: failed ? Tokens.danger : Tokens.ink)
                }
                VStack(alignment: .leading, spacing: Tokens.Space.s) {
                    Text("Restore adds copies and never overwrites your current documents. Recently Deleted items remain deleted. Each backup supports up to 1 GB and 1,000 documents.")
                    Text("No automatic uploads or Scanner account. Cloud storage, if you choose it, is provided by your Files provider.")
                }
                .textRole(.caption)
            }
            .padding(.horizontal, Tokens.Space.m).padding(.vertical, Tokens.Space.l)
            .frame(maxWidth: 672)
            .frame(maxWidth: .infinity)
        }
        .sevenScreenBackground()
        .navigationTitle("Backup & restore").disabled(working)
        .sensoryFeedback(.success, trigger: exported?.id) { _, new in new != nil }
        .sheet(item: $exported) { SevenFileExportPicker(urls: [$0.url]) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data], allowsMultipleSelection: false) { result in
            do { candidate = try result.get().first } catch { failed = true; message = error.localizedDescription }
        }
        .confirmationDialog("Restore copies from this backup?", isPresented: Binding(get: { candidate != nil }, set: { if !$0 { candidate = nil } }), titleVisibility: .visible) {
            if let url = candidate { Button("Add restored copies") { candidate = nil; Task { await restore(url) } } }
        }
    }
    private func export() async {
        working = true; message = nil; defer { working = false }
        do {
            let url = try DocumentProcessing.exportURL(filename: "Scanner backup.scannerbackup")
            try await model.repository.exportBackup(to: url); exported = ShareFile(url: url)
        } catch { failed = true; message = String(localized: "Backup could not be completed. Your library is unchanged.") + " " + error.localizedDescription }
    }
    private func restore(_ url: URL) async {
        working = true; message = nil; defer { working = false }
        let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let count = try await model.repository.restoreBackup(from: url); await model.reload()
            failed = false; message = String(localized: "Restored \(documentCount(count)) as copies.")
        } catch { failed = true; message = String(localized: "This backup could not be restored. Your existing documents are unchanged.") + " " + error.localizedDescription }
    }
}
