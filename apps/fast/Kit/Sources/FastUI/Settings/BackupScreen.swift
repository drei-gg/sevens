import SwiftUI
import SevenKit
import UniformTypeIdentifiers
import FastCore

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .commaSeparatedText] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct BackupScreen: View {
    @Environment(FastModel.self) private var model
    @State private var document: BackupDocument?
    @State private var contentType: UTType = .json
    @State private var exporting = false
    @State private var importing = false
    @State private var importData: Data?
    @State private var preview: ImportPreview?
    @State private var confirmImport = false
    @State private var confirmDelete = false
    @State private var status: String?
    var body: some View {
        Form {
            Section(f("Keep a copy")) {
                Button(f("Export complete JSON backup")) { Task { await export(csv: false) } }
                Button(f("Export records as CSV")) { Task { await export(csv: true) } }.disabled(!model.isReady)
                Text(f("Backups include your private journal and measurements. Choose where to keep them. JSON restores exactly; CSV is for reading in a spreadsheet.")).textRole(.caption)
            }
            if model.isReady {
                Section(f("Bring your records")) {
                    Button(f("Import JSON backup")) { importing = true }
                    Text(f("Preview first. New records are merged; identical copies are skipped. Conflicting IDs or overlapping fasts stop the whole import. Your current preferences stay as they are.")).textRole(.caption)
                }
                Section { Button(f("Delete all records"), role: .destructive) { confirmDelete = true } }
            }
            if let status { Section { Text(status).foregroundStyle(Tokens.muted) } }
        }
        .sevenScreenBackground().navigationTitle(f("Your data"))
        .disabled(model.busy)
        .fileExporter(isPresented: $exporting, document: document, contentType: contentType, defaultFilename: "fast-tracker-\(Date().formatted(.iso8601.year().month().day().dateSeparator(.dash)))") { result in
            if case .success = result { status = f("Export saved.") }
            if case .failure = result { status = f("The export was not saved. Try another location.") }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            do {
                let url = try result.get(); let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= ArchiveCodec.maxBytes else { throw TrackerError.tooLarge }
                let data = try Data(contentsOf: url)
                let incoming = try ArchiveCodec.decode(data, now: Date())
                preview = try ArchiveCodec.merge(incoming, into: model.archive, now: Date())
                importData = data; confirmImport = true
            } catch { status = FastFormat.error(error) }
        }
        .confirmationDialog(f("Import these records?"), isPresented: $confirmImport, titleVisibility: .visible) {
            Button(f("Import")) { if let data = importData { Task { if await model.importBackup(data) { status = f("Import complete.") }; importData = nil; preview = nil } } }
            Button(f("Cancel"), role: .cancel) { importData = nil; preview = nil }
        } message: {
            if let preview {
                Text(previewText(preview))
            }
        }
        .confirmationDialog(f("Delete every fast, journal entry, measurement, plan and rest day?"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(f("Delete all records"), role: .destructive) {
                Task { if await model.change({ archive in let settings = archive.settings; archive = FastArchive(); archive.settings = settings }) { status = f("All records deleted. Preferences were kept.") } }
            }
        } message: { Text(f("This also ends any running timer and removes custom presets. Export a backup first if you want to keep a copy. This cannot be undone.")) }
    }
    private func previewText(_ preview: ImportPreview) -> String {
        let rows = [(f("New records:"), preview.totalAdded), (f("Fasts:"), preview.addedFasts),
                    (f("Journal:"), preview.addedJournal), (f("Measurements:"), preview.addedMetrics),
                    (f("Plans:"), preview.addedPlans), (f("Rest days:"), preview.addedRestDays), (f("Presets:"), preview.addedPresets)]
        return rows.map { label, count in label + " " + count.formatted() }.joined(separator: "\n")
    }
    private func export(csv: Bool) async {
        do {
            contentType = csv ? .commaSeparatedText : .json
            document = BackupDocument(data: csv ? Data(ArchiveCodec.csv(model.archive).utf8) : try await model.repository.originalData())
            exporting = true
        } catch { status = f("Could not read the records for export. Unlock your phone and try again.") }
    }
}
