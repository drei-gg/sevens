import BloomCore
import CoreText
import SevenKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SettingsScreen: View {
  @Environment(BloomModel.self) private var model
  @State private var profile: Profile?
  @State private var erase = false
  @State private var before = false
  var body: some View {
    Form {
      BloomSection("Your preferences") {
        NavigationLink("Make it yours") { TrackingLayoutScreen() }
        NavigationLink("Estimate exclusions") { ExclusionsScreen() }
        Button("Tracking mode and privacy") { profile = model.archive.profile }
        Toggle(
          "Require device authentication",
          isOn: Binding(
            get: { model.lockEnabled }, set: { on in Task { await model.enableLock(on) } }))
          .disabled(model.authenticating)
        Text(
          "Your journal is encrypted on this device. The app lock also hides it when you leave the app."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      BloomSection("Your data") {
        NavigationLink("Reports and selected sharing") { ReportScreen() }
        NavigationLink("Export, backup and import") { BackupScreen() }
        NavigationLink("Apple Health") { HealthScreen() }
        Text(
          "No account, advertising SDK or journal upload. Enabling Watch or widgets sends only their selected snapshot. Family Sharing shares the purchase, never records."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      AboutSection(
        onShowBeforeYouBuy: { before = true },
        notStartedStatus: "Trial not started. Your records remain yours.")
      BloomSection("About") {
        NavigationLink("Guides and sources") { GuidesScreen() }
        AboutVersionRow()
        AboutLicenseRow(repositoryURL: URL(string: "https://github.com/drei-gg/sevens")!)
        AboutNoticesRow(notices: [
          "Uses the shared Alarm scheduling engine, UniFFI, age, Jiff and platform frameworks. See source manifests for licenses.",
          "Cycle estimates and symptom summaries are informational. They do not diagnose conditions, confirm ovulation or provide contraception.",
        ])
      }
      Section {
        Button("Erase all local records", role: .destructive) { erase = true }
      } footer: {
        Text(
          "Exports, Health records you shared, and copies held by other people are separate. Erasing here cannot remove those copies."
        )
      }
    }.navigationTitle("Settings").sevenScreenBackground()
      .sheet(
        item: Binding(
          get: { profile.map { ProfileDraft(profile: $0) } }, set: { profile = $0?.profile })
      ) { ProfileEditor(value: $0.profile, revision: model.archive.revision) }
      .sheet(isPresented: $before) {
        BeforeYouBuyView(content: BloomGate.before) { _ in before = false }
      }
      .confirmationDialog(
        "Erase every local journal entry, plan and pregnancy record?", isPresented: $erase,
        titleVisibility: .visible
      ) { Button("Erase all records", role: .destructive) { Task { await model.erase() } } }
  }
}
struct ProfileDraft: Identifiable {
  var id: String { "profile" }
  let profile: Profile
}
struct ProfileEditor: View {
  @Environment(BloomModel.self) private var model
  @State var value: Profile
  let revision: UInt64
  var body: some View {
    EditorFrame(
      title: "Tracking and privacy",
      save: { await model.save("profile", value, revision: revision) }
    ) {
      BloomSection("Tracking") {
        Picker("Mode", selection: $value.mode) {
          Text("Cycle tracking").tag("cycle")
          Text("Trying to conceive").tag("conceive")
          Text("Pregnancy").tag("pregnancy")
          Text("Postpartum").tag("postpartum")
          Text("Perimenopause").tag("perimenopause")
        }
        Stepper(
          "Usual cycle: \(value.typical_cycle) days", value: $value.typical_cycle, in: 15...90)
        Stepper(
          "Usual period: \(value.typical_period) days", value: $value.typical_period, in: 1...20)
        Toggle("Breastfeeding / lactating", isOn: $value.lactating)
        Toggle("Estimated period reminder", isOn: $value.period_reminder)
        Text(
          "Add a pregnancy record in Care before choosing Pregnancy. Forecasts pause during pregnancy, postpartum, lactation and active hormonal contraception."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      BloomSection("Optional surfaces") {
        Toggle("Discreet reminders", isOn: $value.discreet)
        Toggle("Enable Watch companion", isOn: $value.watch_enabled)
        Toggle("Enable widgets", isOn: $value.widgets_enabled)
        Text(
          "Discreet mode hides plan names and cycle day outside the app. Watch stores today’s reminder snapshot. Turning sharing off clears it when the Watch next connects."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
    }
  }
}
struct BloomDocument: FileDocument {
  static let readableContentTypes: [UTType] = [.data]
  var data: Data
  init(data: Data = Data()) { self.data = data }
  init(configuration: ReadConfiguration) throws {
    data = configuration.file.regularFileContents ?? Data()
  }
  func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
    FileWrapper(regularFileWithContents: data)
  }
}
struct BackupScreen: View {
  @Environment(BloomModel.self) private var model
  @State private var password = ""
  @State private var confirmation = ""
  @State private var encryptedImport = true
  @State private var includeContacts = false
  @State private var includeNotes = false
  @State private var exporting = false
  @State private var importing = false
  @State private var busy = false
  @State private var document = BloomDocument()
  @State private var filename = "cycle-backup.age"
  @State private var preview: Archive?
  var body: some View {
    Form {
      BloomSection("Encrypted backup") {
        SecureField(
          "Passphrase (at least 12 characters)", text: $password,
          prompt: Text("Passphrase (at least 12 characters)").foregroundStyle(Tokens.muted)
        ).textContentType(
          .newPassword)
        SecureField(
          "Repeat passphrase for export", text: $confirmation,
          prompt: Text("Repeat passphrase for export").foregroundStyle(Tokens.muted))
        Button("Export encrypted backup") {
          filename = "cycle-backup.age"
          run {
            guard password == confirmation else {
              throw BloomFailure.message("The passphrases do not match.")
            }
            return try await model.repository.seal(password: password)
          }
        }
        Text(
          "Keep a separate copy of your passphrase. We cannot recover it. This backup includes the full private journal and can restore it on another device."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      BloomSection("Portable export") {
        Toggle("Include sex and contact in CSV", isOn: $includeContacts)
        Toggle("Include private notes in CSV", isOn: $includeNotes)
        Button("Export selected CSV") {
          filename = "cycle-records.csv"
          run {
            try await model.repository.exportCSV(contacts: includeContacts, notes: includeNotes)
          }
        }
        Button("Export full unencrypted JSON") {
          filename = "cycle-records.json"
          run { try await model.repository.exportJSON() }
        }
        Text(
          "CSV and JSON are readable by anyone with the file. Full JSON contains every private field."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      BloomSection("Import") {
        Toggle("Encrypted age backup", isOn: $encryptedImport)
        Button("Choose backup to review") { importing = true }
        Text(
          "Accepts this app’s JSON or encrypted backups. Existing records are preserved; conflicting entries cancel the entire import. New plans and appointment reminders start inactive. This device’s privacy preferences stay in place."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      if let preview {
        BloomSection("Review before importing") {
          Text(
            "\(preview.observations.count) timed observations, \(preview.exclusions.count) exclusions, \(preview.days.count) journal days, \(preview.contacts.count) contacts, \(preview.plans.count) plans, \(preview.doses.count) dose records, \(preview.refills.count) refills, \(preview.pregnancies.count) pregnancies, \(preview.appointments.count) appointments."
          )
          Button("Import these records") {
            busy = true
            Task {
              if await model.importArchive(preview) {
                self.preview = nil
                model.message =
                  "Records imported. Review plans and appointments before enabling their reminders."
              }
              busy = false
            }
          }
          Button("Discard preview", role: .cancel) { self.preview = nil }
        }
      }
      if busy { ProgressView("Working on your backup") }
    }.navigationTitle("Backups and files").sevenScreenBackground().disabled(busy)
      .fileExporter(
        isPresented: $exporting, document: document, contentType: .data, defaultFilename: filename
      ) { result in
        document = BloomDocument()
        if case .failure(let error) = result { model.message = explain(error) }
      }
      .fileImporter(
        isPresented: $importing, allowedContentTypes: [.data, .json], allowsMultipleSelection: false
      ) { result in
        do {
          guard let url = try result.get().first else { return }
          busy = true
          Task {
            defer { busy = false }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            do {
              let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
              guard size <= 17_000_000 else {
                throw BloomFailure.message("The backup is too large.")
              }
              let data = try Data(contentsOf: url)
              preview = try await model.repository.inspectImport(
                data, password: encryptedImport ? password : nil)
            } catch { model.message = explain(error) }
          }
        } catch { model.message = explain(error) }
      }
  }
  func run(_ operation: @escaping @MainActor () async throws -> Data) {
    busy = true
    Task {
      defer { busy = false }
      do {
        document = BloomDocument(data: try await operation())
        exporting = true
      } catch { model.message = explain(error) }
    }
  }
}
struct ReportScreen: View {
  @Environment(BloomModel.self) private var model
  @State private var from = Calendar.current.date(byAdding: .month, value: -3, to: Date()) ?? Date()
  @State private var to = Date()
  @State private var contacts = false
  @State private var notes = false
  @State private var report = ""
  @State private var document = BloomDocument()
  @State private var exporting = false
  var body: some View {
    Form {
      BloomSection("Choose what to share") {
        DatePicker("From", selection: $from, in: ...Date(), displayedComponents: .date)
        DatePicker("Through", selection: $to, in: ...Date(), displayedComponents: .date)
        Toggle("Include sex and contact", isOn: $contacts)
        Toggle("Include private notes", isOn: $notes)
        Button("Build report") {
          Task {
            do {
              report = try await model.repository.report(
                from: BloomDate.day(from), to: BloomDate.day(to), contacts: contacts, notes: notes,
                fahrenheit: BloomTemperature.fahrenheit)
            } catch { model.message = explain(error) }
          }
        }
      }
      if !report.isEmpty {
        BloomSection("Review this exact report") {
          Text(report).textRole(.body).textSelection(.enabled)
          ShareLink("Share reviewed text", item: report)
          Button("Export PDF") {
            document = BloomDocument(data: reportPDF(report))
            exporting = true
          }
          Text(
            "Only the reviewed report is shared. Your partner or clinician receives a copy, not access to your journal."
          ).textRole(.secondary).foregroundStyle(Tokens.muted)
        }
      }
    }.navigationTitle("Report and share").sevenScreenBackground()
      .onChange(of: from) { _, _ in report = "" }.onChange(of: to) { _, _ in report = "" }.onChange(
        of: contacts
      ) { _, _ in report = "" }.onChange(of: notes) { _, _ in report = "" }
      .fileExporter(
        isPresented: $exporting, document: document, contentType: .pdf,
        defaultFilename: "cycle-report.pdf"
      ) { result in
        document = BloomDocument()
        if case .failure(let error) = result { model.message = explain(error) }
      }
  }
}
@MainActor func reportPDF(_ report: String) -> Data {
  let bounds = CGRect(x: 0, y: 0, width: 595, height: 842)
  let renderer = UIGraphicsPDFRenderer(bounds: bounds)
  let style = NSMutableParagraphStyle()
  style.lineSpacing = 3
  style.lineBreakMode = .byWordWrapping
  let attributed = NSAttributedString(
    string: report,
    attributes: [
      .font: UIFont.systemFont(ofSize: 11), .foregroundColor: UIColor.black, .paragraphStyle: style,
    ])
  let framesetter = CTFramesetterCreateWithAttributedString(attributed)
  return renderer.pdfData { context in
    var offset = 0
    repeat {
      context.beginPage()
      let canvas = context.cgContext
      canvas.saveGState()
      canvas.textMatrix = .identity
      canvas.translateBy(x: 0, y: bounds.height)
      canvas.scaleBy(x: 1, y: -1)
      let path = CGPath(rect: bounds.insetBy(dx: 48, dy: 48), transform: nil)
      let frame = CTFramesetterCreateFrame(
        framesetter, CFRange(location: offset, length: 0), path, nil)
      CTFrameDraw(frame, canvas)
      canvas.restoreGState()
      let visible = CTFrameGetVisibleStringRange(frame)
      guard visible.length > 0 else { break }
      offset += visible.length
    } while offset < attributed.length
  }
}
struct GuidesScreen: View {
  var body: some View {
    List {
      BloomSection("Know your records") {
        Text(
          "Cycle day starts with the first day of a period. Log a new period start when needed; spotting is recorded separately. Missing days are unknown, not symptom-free days."
        )
        Text(
          "Your estimates use recent logged cycles. Variable cycles, hormonal contraception and life-stage changes make calendar predictions less useful. No date in this app can establish contraceptive protection."
        )
        Text(
          "Use symptom and dose reports to prepare for a conversation with a clinician. This app does not assess PCOS, endometriosis, pregnancy health or menopause."
        )
      }
      BloomSection("Read the source") {
        Link(
          "NHS · Periods and the menstrual cycle",
          destination: URL(
            string: "https://www.nhs.uk/conditions/periods/fertility-in-the-menstrual-cycle/")!)
        Link(
          "NHS · Period problems and when to seek care",
          destination: URL(string: "https://www.nhs.uk/conditions/periods/period-problems/")!)
        Link(
          "NHS · Missed or late periods",
          destination: URL(string: "https://www.nhs.uk/symptoms/missed-or-late-periods/")!)
        Link(
          "NHS · Pregnancy tests",
          destination: URL(
            string: "https://www.nhs.uk/pregnancy/trying-for-a-baby/doing-a-pregnancy-test/")!)
        Link(
          "NHS · Perimenopause symptoms",
          destination: URL(
            string: "https://www.nhs.uk/conditions/menopause-and-perimenopause/symptoms/")!)
      }
      BloomSection("Medication reminders") {
        Text(
          "Use the medicine leaflet or your clinician’s instructions to configure your schedule. Follow the leaflet or ask a pharmacist about missed doses, interactions or a change of time zone. Reminders record your choices; they do not prescribe a dose."
        )
      }
      BloomSection("Family purchase, private lives") {
        Text(
          "Family Sharing unlocks the app for eligible family members with their own Apple accounts. Each person has a separate journal. There is no family dashboard, parent access or automatic partner sharing."
        )
      }
    }.navigationTitle("Guides and sources").sevenScreenBackground()
  }
}
