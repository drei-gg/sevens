import SwiftUI
import SevenKit
import UniformTypeIdentifiers

struct BackupScreen: View {
    @Environment(ScannerModel.self) private var model
    @State private var exported: ShareFile?
    @State private var importing = false
    @State private var candidate: URL?
    @State private var working = false
    @State private var message: String?
    var body: some View {
        Form {
            Section {
                Text("Back up documents, original pages, adjustments, notes and Recently Deleted in one portable file. Save it on your device, an external drive, or a provider you choose in Files.")
                Text("No automatic uploads or Scanner account. Cloud storage, if you choose it, is provided by your Files provider.").font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                Button("Export library backup") { Task { await export() } }.accessibilityIdentifier("backup.export")
                Button("Restore from backup") { importing = true }.accessibilityIdentifier("backup.restore")
                Text("Restore adds copies and never overwrites your current documents. Recently Deleted items remain deleted. Each backup supports up to 1 GB and 1,000 documents.").font(.footnote).foregroundStyle(.secondary)
            }
            if working { ProgressView("Working…") }
            if let message { Text(message) }
        }.navigationTitle("Backup & restore").disabled(working)
            .sheet(item: $exported) { SevenFileExportPicker(urls: [$0.url]) }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.data], allowsMultipleSelection: false) { result in
                do { candidate = try result.get().first } catch { message = error.localizedDescription }
            }
            .confirmationDialog("Restore copies from this backup?", isPresented: Binding(get: { candidate != nil }, set: { if !$0 { candidate = nil } })) {
                if let url = candidate { Button("Add restored copies") { candidate = nil; Task { await restore(url) } } }
            }
    }
    private func export() async {
        working = true; message = nil; defer { working = false }
        do {
            let url = try DocumentProcessing.exportURL(filename: "Scanner backup.scannerbackup")
            try await model.repository.exportBackup(to: url); exported = ShareFile(url: url)
        } catch { message = "Backup could not be completed. Your library is unchanged. " + error.localizedDescription }
    }
    private func restore(_ url: URL) async {
        working = true; message = nil; defer { working = false }
        let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let count = try await model.repository.restoreBackup(from: url); await model.reload()
            message = "Restored \(count) document copies."
        } catch { message = "This backup could not be restored. Your existing documents are unchanged. " + error.localizedDescription }
    }
}
