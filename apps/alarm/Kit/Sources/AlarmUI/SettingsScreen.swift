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
                Button("Ring a test alarm in 10 seconds", systemImage: "alarm") { Task { await model.testAlarm() } }
                Button("Request permissions", systemImage: "hand.raised") { Task { await model.authorize() } }
                Link(destination: URL(string: UIApplication.openSettingsURLString)!) { Label("Open system settings", systemImage: "gear") }
                Button("Retry schedule registration", systemImage: "arrow.clockwise") { Task { await model.synchronize() } }
            } header: { Text("Alarm delivery").foregroundStyle(Tokens.muted) } footer: {
                Text("Uses your selected tone and the system alarm volume. Test on your phone before relying on an alarm. iOS provides its own Stop control.").textRole(.caption)
            }
            Section {
                NavigationLink("New alarm defaults") { AlarmDefaultsEditor(defaults: model.archive.alarmDefaults ?? AlarmDefaults()) }
            } header: { Text("Less setup").foregroundStyle(Tokens.muted) }
            Section {
                Button("Export JSON backup", systemImage: "square.and.arrow.up") { do { document = BackupDocument(data: try BackupCodec.encode(model.archive)); exportType = .json; exportName = "alarm-loops-backup"; exporting = true } catch { model.error = error.localizedDescription } }
                Button("Export history CSV", systemImage: "tablecells") { document = BackupDocument(data: Data(BackupCodec.csv(model.archive).utf8)); exportType = .commaSeparatedText; exportName = "alarm-loops-history"; exporting = true }
                Button("Import backup", systemImage: "square.and.arrow.down") { importing = true }
            } header: { Text("Your data").foregroundStyle(Tokens.muted) } footer: {
                Text("Stored on this iPhone. No accounts, ads or analytics. Your device backup may include these records. Imports add new routines turned off; they never arm alarms on their own.").textRole(.caption)
            }
            Section {
                Button("Export diagnostics", systemImage: "stethoscope") { Task { do { document = BackupDocument(data: try await model.diagnosticData()); exportType = .json; exportName = "alarm-loops-diagnostics"; exporting = true } catch { model.error = error.localizedDescription } } }
            } header: { Text("Troubleshooting").foregroundStyle(Tokens.muted) } footer: {
                Text("A local report with app and OS versions, alarm permission, registration counts and error flags. Leaves out names, schedules, scanned codes, phrases and history. Nothing is sent automatically. Registration counts don’t prove an alarm rang.").textRole(.caption)
            }
            AboutSection(onShowBeforeYouBuy: { before = true }, notStartedStatus: "Free trial not started. Existing alarms and history remain available.")
            Section {
                AboutVersionRow(); AboutLicenseRow(repositoryURL: URL(string: "https://github.com/drei-gg/sevens")!); AboutNoticesRow(notices: ["Apple frameworks and SevenKit. No third-party libraries."])
            } header: { Text("About").foregroundStyle(Tokens.muted) } footer: {
                Text("Only Apple handles purchase and restore requests. Scanned mission codes stay on this phone and are never opened as links.").textRole(.caption)
            }
        }
        .sevenScreenBackground()
        .navigationTitle("Settings")
        .sheet(isPresented: $before) { BeforeYouBuyView(content: AlarmGate.before) { _ in before = false } }
        .fileExporter(isPresented: $exporting, document: document, contentType: exportType, defaultFilename: exportName) { if case let .failure(error) = $0 { model.error = error.localizedDescription } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            do { let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }; let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0; guard size <= BackupCodec.maxBytes else { throw LoopError.invalid("This backup is larger than 32 MB.") }; pendingImport = try BackupCodec.decode(Data(contentsOf: url)); confirmImport = true } catch { model.error = error.localizedDescription }
        }
        .confirmationDialog("Import \(pendingImport?.routines.count ?? 0) routines and \(pendingImport?.events.count ?? 0) history records? Existing routine IDs are kept; new routines start turned off.", isPresented: $confirmImport, titleVisibility: .visible) {
            Button("Import routines turned off") { if let incoming = pendingImport { Task { _ = await model.mutate { $0 = try BackupCodec.merge(incoming, into: $0) }; pendingImport = nil } } }
        }
    }
}
