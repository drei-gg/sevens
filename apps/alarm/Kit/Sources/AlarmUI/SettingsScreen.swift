import SwiftUI
import UniformTypeIdentifiers
import SevenKit
import AlarmCore

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .commaSeparatedText] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/// Settings: delivery status first (what the phone will actually do), then setup, your data and
/// About. Explanations sit in section footers.
struct SettingsScreen: View {
    @Environment(AlarmModel.self) private var model
    @State private var before = false
    @State private var importing = false
    @State private var exporting = false
    @State private var document = BackupDocument(data: Data())
    @State private var exportType = UTType.json
    @State private var exportName = "alarm-loops-backup"
    @State private var pendingImport: LoopArchive?
    @State private var confirmImport = false
    var body: some View {
        Form {
            Section {
                Label { VStack(alignment: .leading, spacing: Tokens.Space.xs) { Text(model.driver.status).textRole(.rowTitle); if model.scheduling != model.driver.status { Text(model.scheduling).textRole(.caption) } } }
                    icon: { Image(systemName: "bell.badge").foregroundStyle(Tokens.muted) }
                    .accessibilityElement(children: .combine)
                Button(f("Ring a test alarm in 10 seconds"), systemImage: "alarm") { Task { await model.testAlarm() } }
                Button(f("Request permissions"), systemImage: "hand.raised") { Task { await model.authorize() } }
                Link(destination: URL(string: UIApplication.openSettingsURLString)!) { Label(f("Open system settings"), systemImage: "gear") }
                Button(f("Retry schedule registration"), systemImage: "arrow.clockwise") { Task { await model.synchronize() } }
            } header: { Text(f("Alarm delivery")).foregroundStyle(Tokens.muted) } footer: {
                Text(f("Uses your selected tone and the system alarm volume. Test on your phone before relying on an alarm. iOS provides its own Stop control.")).textRole(.caption)
            }
            Section {
                Text(f("Add Saved schedule from the Home Screen widget gallery."))
                if let error = model.widgetError { Text(error).foregroundStyle(Tokens.danger) }
            } header: { Text(f("Home Screen widget")).foregroundStyle(Tokens.muted) } footer: {
                Text(f("Shows upcoming saved alarms and loops, including routine names. Refreshes after changes and expires after a day without an app check. A schedule is not proof of delivery.")).textRole(.caption)
            }
            Section {
                NavigationLink(f("New alarm defaults")) { AlarmDefaultsEditor(defaults: model.archive.alarmDefaults ?? AlarmDefaults()) }
            } header: { Text(f("Less setup")).foregroundStyle(Tokens.muted) }
            Section {
                Text(f("JSON backups retain sound selections but not audio files. Re-import missing sounds or use their built-in fallback.")).textRole(.caption)
                Button(f("Export JSON backup"), systemImage: "square.and.arrow.up") { do { document = BackupDocument(data: try BackupCodec.encode(model.archive)); exportType = .json; exportName = "alarm-loops-backup"; exporting = true } catch { model.error = error.localizedDescription } }
                Button(f("Export history CSV"), systemImage: "tablecells") { document = BackupDocument(data: Data(BackupCodec.csv(model.archive).utf8)); exportType = .commaSeparatedText; exportName = "alarm-loops-history"; exporting = true }
                Button(f("Import backup"), systemImage: "square.and.arrow.down") { importing = true }
            } header: { Text(f("Your data")).foregroundStyle(Tokens.muted) } footer: {
                Text(f("Stored on this iPhone. No accounts, ads or analytics. Your device backup may include these records. Imports add new routines turned off; they never arm alarms on their own.")).textRole(.caption)
            }
            Section {
                Button(f("Export diagnostics"), systemImage: "stethoscope") { Task { do { document = BackupDocument(data: try await model.diagnosticData()); exportType = .json; exportName = "alarm-loops-diagnostics"; exporting = true } catch { model.error = error.localizedDescription } } }
            } header: { Text(f("Troubleshooting")).foregroundStyle(Tokens.muted) } footer: {
                Text(f("A local report with app and OS versions, alarm permission, registration counts and error flags. Leaves out names, schedules, scanned codes, phrases and history. Nothing is sent automatically. Registration counts don’t prove an alarm rang.")).textRole(.caption)
            }
            AboutSection(onShowBeforeYouBuy: { before = true }, notStartedStatus: f("Free trial not started. Existing alarms and history remain available."))
            Section {
                AboutVersionRow(); AboutLicenseRow(repositoryURL: URL(string: "https://github.com/drei-gg/sevens")!); AboutNoticesRow(notices: ["Shared Rust core and SevenKit. Full dependency inventory: https://github.com/drei-gg/sevens/blob/main/THIRD-PARTY.md", Bundle.module.url(forResource: "Hound", withExtension: "txt").flatMap { url in try? String(contentsOf: url, encoding: .utf8) } ?? f("Hound 3.5.1, Apache-2.0.")])
            } header: { Text(f("About")).foregroundStyle(Tokens.muted) } footer: {
                Text(f("Only Apple handles purchase and restore requests. Scanned mission codes stay on this phone and are never opened as links.")).textRole(.caption)
            }
        }
        .sevenScreenBackground()
        .navigationTitle(f("Settings"))
        .sheet(isPresented: $before) { BeforeYouBuyView(content: AlarmGate.before) { _ in before = false } }
        .fileExporter(isPresented: $exporting, document: document, contentType: exportType, defaultFilename: exportName) { if case let .failure(error) = $0 { model.error = error.localizedDescription } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            do { let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }; let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0; guard size <= BackupCodec.maxBytes else { throw LoopFailure.invalid(f("This backup is larger than 32 MB.")) }; pendingImport = try BackupCodec.decode(Data(contentsOf: url)); confirmImport = true } catch { model.error = error.localizedDescription }
        }
        .confirmationDialog(String(localized: "Import \(pendingImport?.routines.count ?? 0) routines and \(pendingImport?.events.count ?? 0) history records? Existing routine IDs are kept; new routines start turned off.", bundle: .module), isPresented: $confirmImport, titleVisibility: .visible) {
            Button(f("Import routines turned off")) { if let incoming = pendingImport { Task { _ = await model.mutate { $0 = try BackupCodec.merge(incoming, into: $0) }; pendingImport = nil } } }
        }
    }
}
