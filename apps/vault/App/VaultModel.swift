import SwiftUI
import SevenKit
import VaultCore
import VaultStorage
import UniformTypeIdentifiers
import LocalAuthentication
import Security
import PhotosUI
import SevenBackup
import BackgroundTasks

struct PdfRecognitionRange { let item: VaultItem; let pages: UInt32 }
struct RecognizedImageText { let item: VaultItem; let text: String }
struct PresentedFiles: Identifiable { var id = UUID(); let urls: [URL]; var sharing = false; var filesExport = false; var recognized: RecognizedImageText?; var pdfRange: PdfRecognitionRange?; var batch: RecognitionQueue? }
@Observable @MainActor final class VaultModel {
    let repository: VaultRepository
    let store: Store
    let automatic: AutomaticBackup
    var automaticStatus: BackupStatus?
    var automaticMessage: String?
    var automaticWorking = false
    var automaticNeedsPreparation = false
    private var automaticTask: Task<Void, Never>?
    private var deliveryTask: Task<Void, Never>?
    var library: VaultLibrary?
    var busy = false
    var progress = ""
    var error: String?
    var recoveryKey: String?
    /// The shown recovery key came from a version 1 → 2 migration and replaces the old one.
    var recoveryKeyReplacesOld = false
    /// The vault whose key Face ID / Touch ID holds (its Keychain item's account), if any.
    var biometricVaultID: UUID? = UserDefaults.standard.string(forKey: BiometricKey.defaultsKey).flatMap(UUID.init(uuidString:))
    var presentation: PresentedFiles?
    var generation = 0
    var importResult = ""
    private var started = false
    private var operation = 0
    private var recognitionTask: Task<String, Error>?
    init() {
        let config = SevenConfig(bundle: .main) ?? SevenConfig(trialProductID: "org.example.sevens.vault.trial7", unlockProductID: "org.example.sevens.vault.unlock")
        var folder = "Vault"
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-vault.ui") {
            folder = "VaultUITests"
            let defaults = UserDefaults(suiteName: "Vault.UITests")!
            let source = LocalSource(defaults: defaults); source.apply(.unlocked)
            store = Store(config: config, source: source, defaults: defaults)
            store.markBeforeYouBuyShown(); store.markTrialEndedShown()
        } else { store = Store(config: config, trialPolicy: .onFirstLaunch) }
        #else
        store = Store(config: config, trialPolicy: .onFirstLaunch)
        #endif
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(folder)
        let temporary = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent(folder + "-plaintext")
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-vault.ui") && ProcessInfo.processInfo.arguments.contains("-vault.reset") { try? FileManager.default.removeItem(at: root) }
        #endif
        repository = VaultRepository(root: root, temporary: temporary)
        let queueRoot = root.deletingLastPathComponent().appendingPathComponent(folder + "-backup-queue")
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-vault.ui") && ProcessInfo.processInfo.arguments.contains("-vault.reset") { try? FileManager.default.removeItem(at: queueRoot) }
        #endif
        automatic = AutomaticBackup(root: queueRoot)
        BackupBackground.queue = automatic
    }
    func start() async {
        guard !started else { return }; started = true
        BackupBackground.queue = automatic
        do { try await repository.prepare() } catch { self.error = error.vaultMessage }
        await cleanBiometricOrphans()
        do { try await automatic.prepare() } catch { automaticMessage = error.vaultMessage }
        await store.start()
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-vault.routes") && ProcessInfo.processInfo.arguments.contains("-vault.reset") {
            do {
                _ = try await repository.create(name: "PIN library", passphrase: "00123456", kind: .pin)
                _ = try await repository.create(name: "Pattern library", passphrase: "0,5,10,15,20,21,22,23,24", kind: .pattern)
                await repository.lock()
            } catch { self.error = error.vaultMessage }
        }
        #endif
        await retryAutomaticBackups()
    }
    func lock() {
        if let batch = presentation?.batch { batch.stopped = true; batch.text = "" }
        recognitionTask?.cancel(); recognitionTask = nil
        automaticTask?.cancel(); automaticTask = nil; automaticStatus = nil; automaticMessage = nil
        generation += 1; operation += 1; library = nil; recoveryKey = nil; recoveryKeyReplacesOld = false; presentation = nil; error = nil; importResult = ""
        ThumbnailCache.shared.clear()
        // Stay busy until the actor has discarded keys and any in-flight operation has finished.
        busy = true; progress = String(localized: "Locking…")
        let token = operation
        Task { await repository.lock(); if token == operation { busy = false; progress = "" }; await retryAutomaticBackups() }
    }
    func create(name: String, passphrase: String, kind: VaultCredentialKind = .passphrase) async {
        guard store.paidFeaturesAvailable else { error = String(localized: "Start the trial or unlock to add a vault."); return }
        await perform(String(localized: "Creating encrypted vault…")) {
            let result = try await self.repository.create(name: name, passphrase: passphrase, kind: kind)
            return (result.library, result.recoveryKey)
        }
    }
    func unlock(_ credential: String, recovery: Bool, kind: VaultCredentialKind = .passphrase) async {
        await perform(String(localized: "Opening vault…")) { try await self.migrated(self.repository.unlock(credential: credential, recovery: recovery, kind: kind)) }
    }
    /// After an unlock or restore: when the repository just migrated the vault to version 2, show
    /// the new recovery key, and re-wrap Face ID's copy (a version 1 key no longer opens it).
    private func migrated(_ value: VaultLibrary) async throws -> (VaultLibrary, String?) {
        guard let replacement = await repository.takeMigratedRecoveryKey() else { return (value, nil) }
        recoveryKeyReplacesOld = true
        if biometricVaultID == value.id {
            do { let (id, data) = try await repository.biometricMaterial(); try BiometricKey.save(data, id: id) }
            catch { disableBiometrics(announce: false) }
        }
        return (value, replacement)
    }
    private func perform(_ label: String, action: () async throws -> (VaultLibrary, String?)) async {
        guard !busy else { return }; busy = true; progress = label; operation += 1; let token = operation
        defer { if token == operation { busy = false; progress = "" } }
        do { let (value, recovery) = try await action(); guard token == operation else { return }; library = value; recoveryKey = recovery; scheduleAutomaticBackup() }
        catch { if token == operation { self.error = error.vaultMessage } }
    }
    /// A change to the open vault. True when it committed and this session is still the one shown.
    @discardableResult func update(_ label: String = String(localized: "Saving…"), action: () async throws -> Void) async -> Bool {
        guard !busy, library != nil else { return false }; busy = true; progress = label; let token = operation
        defer { if token == operation { busy = false; progress = "" } }
        do {
            try await action(); let value = try await repository.snapshot()
            guard token == operation else { return false }
            library = value; scheduleAutomaticBackup(); return true
        } catch { if token == operation { self.error = error.vaultMessage }; return false }
    }
    func importFiles(_ urls: [URL], folder: UUID?) async {
        guard !busy, library != nil, store.paidFeaturesAvailable else { return }
        busy = true; let token = operation; var successes = 0; var failures: [String] = []
        defer { if token == operation { busy = false; progress = "" } }
        for (index, url) in urls.enumerated() {
            guard token == operation else { return }
            progress = String(localized: "Encrypting \(index + 1) of \(urls.count)…")
            let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let type = (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType?.identifier) ?? "public.data"
                _ = try await repository.importFile(url, name: url.lastPathComponent, type: type, folder: folder); successes += 1
            } catch { failures.append("\(url.lastPathComponent): \(error.vaultMessage)") }
        }
        guard token == operation else { return }
        library = try? await repository.snapshot(); scheduleAutomaticBackup()
        importResult = String(localized: "\(successes) imported. Originals stay where they were.")
        if !failures.isEmpty { error = String(localized: "\(failures.count) could not be imported:") + "\n" + failures.joined(separator: "\n") }
    }
    func importPhotos(_ items: [PhotosPickerItem], folder: UUID?) async {
        guard !busy, library != nil, store.paidFeaturesAvailable else { return }
        busy = true; let token = operation; var successes = 0; var failures: [String] = []
        defer { if token == operation { busy = false; progress = "" } }
        for (index, item) in items.enumerated() {
            guard token == operation else { return }
            progress = String(localized: "Loading and encrypting \(index + 1) of \(items.count)…")
            do {
                guard let media = try await item.loadTransferable(type: IncomingMedia.self) else { throw VaultError.Unsupported }
                defer { try? FileManager.default.removeItem(at: media.url.deletingLastPathComponent()) }
                guard token == operation else { return }
                let type = (try? media.url.resourceValues(forKeys: [.contentTypeKey]).contentType?.identifier) ?? "public.data"
                _ = try await repository.importFile(media.url, name: media.url.lastPathComponent, type: type, folder: folder); successes += 1
            } catch { failures.append(String(localized: "Item \(index + 1): \(error.vaultMessage)")) }
        }
        guard token == operation else { return }
        library = try? await repository.snapshot(); scheduleAutomaticBackup(); importResult = String(localized: "\(successes) imported. Originals stay in Photos.")
        if !failures.isEmpty { error = String(localized: "\(failures.count) imports failed:") + "\n" + failures.joined(separator: "\n") }
    }
    func preview(_ item: VaultItem) async {
        guard !busy else { return }; let token = operation
        await update(String(localized: "Preparing preview…")) {
            let url = try await self.repository.export(item.id)
            if token == self.operation { self.presentation = PresentedFiles(urls: [url]) }
        }
    }
    func prepareRecognitionQueue(_ ids: Set<UUID>) {
        guard !busy, let library else { return }
        let supported = library.items.filter { UTType($0.type)?.conforms(to: .image) == true || $0.type == UTType.pdf.identifier }.map(\.id)
        let plan = recognitionBatchPlan(items: library.items, selected: Array(ids), supported: supported)
        if let issue = plan.issue { error = issue; return }
        presentation = PresentedFiles(urls: [], batch: RecognitionQueue(plan: plan))
    }
    func stopRecognitionQueue(_ queue: RecognitionQueue) {
        queue.stopped = true; queue.text = ""
        recognitionTask?.cancel()
    }
    func recognizeBatchItem(_ queue: RecognitionQueue) async {
        guard !busy, presentation?.batch === queue, !queue.stopped, let item = queue.item else { return }
        let token = operation; queue.failure = nil
        await update(String(localized: "Recognizing file \(queue.index + 1) of \(queue.plan.items.count)…")) {
            do {
                let url = try await self.repository.export(item.id)
                defer { try? FileManager.default.removeItem(at: url) }
                guard token == self.operation, !queue.stopped else { return }
                let pdf = item.type == UTType.pdf.identifier
                let limit = queue.plan.maximumPdfPages
                let task = Task.detached(priority: .userInitiated) {
                    let text: String
                    if pdf {
                        let count = try VaultTextRecognition.pageCount(url)
                        guard count <= limit else { throw BatchRecognitionError.pageLimit(limit) }
                        text = try VaultTextRecognition.readPDF(url, first: 1, last: count)
                    } else { text = try VaultTextRecognition.read(url) }
                    return try recognitionReviewText(text: text)
                }
                self.recognitionTask = task
                defer { if token == self.operation { self.recognitionTask = nil } }
                let text = try await task.value
                guard token == self.operation, self.presentation?.batch === queue, !queue.stopped else { return }
                queue.text = text; queue.name = RecognizedTextScreen.defaultName(item); queue.ready = true
            } catch {
                if token == self.operation && !queue.stopped { queue.failure = error.vaultMessage }
            }
        }
    }
    func saveBatchItem(_ queue: RecognitionQueue) async {
        guard !busy, presentation?.batch === queue, queue.ready, !queue.stopped, store.paidFeaturesAvailable, let item = queue.item else { return }
        let name = queue.name, text = queue.text, token = operation
        await update {
            try await self.repository.addNote(name: name, text: recognitionReviewText(text: text), folder: item.folder)
            guard token == self.operation, self.presentation?.batch === queue else { return }
            queue.next(savedNote: true)
        }
    }
    func recognizeText(_ item: VaultItem) async {
        guard !busy else { return }
        if UTType(item.type)?.conforms(to: .pdf) == true {
            let token = operation
            await update(String(localized: "Reading PDF pages…")) {
                let url = try await self.repository.export(item.id)
                defer { try? FileManager.default.removeItem(at: url) }
            guard token == self.operation else { return }
                let count = try await Task.detached(priority: .userInitiated) { try VaultTextRecognition.pageCount(url) }.value
                guard token == self.operation else { return }
                self.presentation = PresentedFiles(urls: [], pdfRange: PdfRecognitionRange(item: item, pages: count))
            }
            return
        }
        guard UTType(item.type)?.conforms(to: .image) == true else { return }
        let token = operation
        await update(String(localized: "Recognizing text on this device…")) {
            let url = try await self.repository.export(item.id)
            defer { try? FileManager.default.removeItem(at: url) }
            guard token == self.operation else { return }
            let task = Task.detached(priority: .userInitiated) { try VaultTextRecognition.read(url) }
            self.recognitionTask = task
            defer { if token == self.operation { self.recognitionTask = nil } }
            let text = try await task.value
            guard token == self.operation else { return }
            self.presentation = PresentedFiles(urls: [], recognized: RecognizedImageText(item: item, text: text))
        }
    }
    func recognizePDF(_ draft: PdfRecognitionRange, first: UInt32, last: UInt32) async {
        guard !busy, presentation?.pdfRange?.item.id == draft.item.id,
              recognitionPlan(pageCount: draft.pages, firstPage: first, lastPage: last).issue == nil else { return }
        let token = operation
        await update(String(localized: "Recognizing PDF on this device…")) {
            let url = try await self.repository.export(draft.item.id)
            defer { try? FileManager.default.removeItem(at: url) }
            guard token == self.operation else { return }
            let task = Task.detached(priority: .userInitiated) { try VaultTextRecognition.readPDF(url, first: first, last: last) }
            self.recognitionTask = task
            defer { if token == self.operation { self.recognitionTask = nil } }
            let text = try await task.value
            guard token == self.operation else { return }
            self.presentation = PresentedFiles(id: self.presentation?.id ?? UUID(), urls: [], recognized: RecognizedImageText(item: draft.item, text: text))
        }
    }
    func export(_ ids: Set<UUID>) async {
        guard !ids.isEmpty else { return }; let token = operation
        await update(String(localized: "Preparing originals…")) {
            var urls: [URL] = []; var failures: [String] = []
            for id in ids.sorted(by: { $0.uuidString < $1.uuidString }) {
                guard token == self.operation else { return }
                do { urls.append(try await self.repository.export(id)) } catch { failures.append(error.vaultMessage) }
            }
            guard token == self.operation else { return }
            if !urls.isEmpty { self.presentation = PresentedFiles(urls: urls, sharing: true) }
            if !failures.isEmpty { self.error = String(localized: "\(failures.count) exports failed. Successful originals are ready.") + "\n" + failures.joined(separator: "\n") }
        }
    }
    func backup() async {
        let token = operation
        await update(String(localized: "Verifying and preparing encrypted backup…")) {
            let url = try await self.repository.backup()
            if token == self.operation { self.presentation = PresentedFiles(urls: [url], sharing: true, filesExport: true) }
        }
    }
    func restore(_ url: URL, credential: String, recovery: Bool, kind: VaultCredentialKind = .passphrase) async {
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        await perform(String(localized: "Verifying backup…")) { try await self.migrated(self.repository.restore(url, credential: credential, recovery: recovery, kind: kind)) }
    }
    func cleanPresentation() { if let batch = presentation?.batch { stopRecognitionQueue(batch) }; presentation = nil; Task { try? await repository.clearTemporary() } }
    /// One vault at a time: enrolling this vault first deletes every other vault's Keychain item.
    func enableBiometrics() async {
        let enabled = await update {
            let (id, data) = try await self.repository.biometricMaterial()
            for account in BiometricCustody.enrolling(id.uuidString, stored: self.biometricVaultID?.uuidString, items: BiometricKey.accounts()) {
                BiometricKey.remove(account: account)
            }
            try BiometricKey.save(data, id: id)
            self.setBiometricVault(id)
        }
        if enabled { announce(String(localized: "Face ID or Touch ID unlock is on for this vault.")) }
    }
    func replaceRecoveryKey() async {
        let token = operation
        await update(String(localized: "Replacing recovery key…")) {
            let replacement = try await self.repository.replaceRecoveryKey()
            if token == self.operation { self.recoveryKey = replacement }
        }
    }
    /// "I’ve saved it": the index stops asking for a replacement, then the key leaves memory. A
    /// failed save keeps the key on screen to retry.
    func confirmRecoveryKey() async {
        let token = operation
        await update { try await self.repository.confirmRecoveryKey(); if token == self.operation { self.recoveryKey = nil; self.recoveryKeyReplacesOld = false } }
    }
    /// The open vault's recovery key was issued but never confirmed as saved (Vault locked or quit
    /// while it was shown): it must be replaced before the library opens.
    var needsRecoveryReplacement: Bool { library?.recoveryUnconfirmed == true && recoveryKey == nil }
    /// Restored with its recovery key: the backup's own secret may already open another vault here,
    /// so a new one must be set (with the usual duplicate check) before the library opens.
    var needsNewSecret: Bool { library?.secretChangeRequired == true }
    /// Removes the stored vault's Keychain item (whichever vault is open, if any) and any stray one.
    func disableBiometrics(announce shouldAnnounce: Bool = true) {
        for account in BiometricCustody.removing(stored: biometricVaultID?.uuidString, items: BiometricKey.accounts()) { BiometricKey.remove(account: account) }
        setBiometricVault(nil)
        if shouldAnnounce { announce(String(localized: "Biometric unlock is off.")) }
    }
    /// At launch: drop the stored ID when its vault is gone, and every Keychain item but the stored
    /// vault's. Skipped when the vault list can't be read, so nothing is deleted on a guess.
    func cleanBiometricOrphans() async {
        guard let ids = try? await repository.vaultIDs() else { return }
        let plan = BiometricCustody.cleanup(stored: biometricVaultID?.uuidString, vaults: Set(ids.map(\.uuidString)), items: BiometricKey.accounts())
        for account in plan.remove { BiometricKey.remove(account: account) }
        if !plan.keep && biometricVaultID != nil { setBiometricVault(nil) }
    }
    private func setBiometricVault(_ id: UUID?) {
        if let id { UserDefaults.standard.set(id.uuidString, forKey: BiometricKey.defaultsKey) }
        else { UserDefaults.standard.removeObject(forKey: BiometricKey.defaultsKey) }
        biometricVaultID = id
    }
    var hasBiometricKey: Bool { biometricVaultID != nil }
    func announce(_ text: String) { AccessibilityNotification.Announcement(text).post() }
    func unlockBiometrics() async {
        guard let id = biometricVaultID else { return }
        await perform(String(localized: "Authenticating…")) {
            let data = try await BiometricKey.read(id: id)
            return (try await self.repository.unlock(id: id, biometricKey: data), nil)
        }
    }
    #if DEBUG
    var testBackupFolder: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("VaultUITestProvider") }
    func enableTestBackup() async {
        guard ProcessInfo.processInfo.arguments.contains("-vault.ui") else { return }
        do {
            try FileManager.default.createDirectory(at: testBackupFolder, withIntermediateDirectories: true)
            await enableAutomaticBackup(in: testBackupFolder)
        } catch { self.error = error.vaultMessage }
    }
    func verifyTestBackup() async {
        guard ProcessInfo.processInfo.arguments.contains("-vault.ui"), let value = library else { return }
        do {
            guard let status = try await automatic.status(target: value.id), let revision = status.copiedRevision,
                  revision == (try await repository.backupRevision()) else { throw BackupFailure.invalidArtifact }
            let file = testBackupFolder.appendingPathComponent(value.id.uuidString + "-" + revision + ".vault7")
            let root = testBackupFolder.appendingPathComponent("restore-" + UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: root) }
            let restored = VaultRepository(root: root.appendingPathComponent("vault"), temporary: root.appendingPathComponent("tmp"))
            try await restored.prepare()
            let result = try await restored.restore(file, credential: "several private testing words", recovery: false)
            guard result.items.count == value.items.count else { throw BackupFailure.changedCopy }
            automaticMessage = "Verified automatic backup: \(result.items.count) files"
        } catch { self.error = error.vaultMessage }
    }
    func fixture() async {
        guard ProcessInfo.processInfo.arguments.contains("-vault.ui") else { return }
        await update(String(localized: "Importing sample originals…")) {
            if ProcessInfo.processInfo.arguments.contains("-vault.pdf") {
                let pdf = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
                let data = pdf.pdfData { context in
                    for text in ["OUTSIDE FIRST PAGE", "PRIVATE ARCHIVE 7429", "OUTSIDE LAST PAGE"] {
                        context.beginPage()
                        (text as NSString).draw(at: CGPoint(x: 40, y: 150), withAttributes: [.font: UIFont.systemFont(ofSize: 28), .foregroundColor: UIColor.black])
                    }
                }
                let url = await self.repository.temporary.appendingPathComponent("Selected pages.pdf")
                defer { try? FileManager.default.removeItem(at: url) }
                try data.write(to: url, options: .completeFileProtection)
                let item = try await self.repository.importFile(url, name: "Selected pages.pdf", type: UTType.pdf.identifier)
                let original = try await self.repository.export(item.id)
                defer { try? FileManager.default.removeItem(at: original) }
                guard try Data(contentsOf: original) == data else { throw VaultError.Damaged }
                return
            }
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: 640, height: 480))
            let image = renderer.image { context in
                UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: 640, height: 480))
                UIColor.systemYellow.setFill(); context.cgContext.fillEllipse(in: CGRect(x: 440, y: 50, width: 100, height: 100))
                UIColor.systemIndigo.setFill(); let path = UIBezierPath(); path.move(to: CGPoint(x: 0, y: 480)); path.addLine(to: CGPoint(x: 240, y: 170)); path.addLine(to: CGPoint(x: 640, y: 480)); path.fill()
                UIColor.white.setFill(); context.fill(CGRect(x: 20, y: 350, width: 600, height: 100))
                ("PRIVATE ARCHIVE 7429" as NSString).draw(at: CGPoint(x: 40, y: 380), withAttributes: [.font: UIFont.systemFont(ofSize: 36), .foregroundColor: UIColor.black])
            }
            let url = await self.repository.temporary.appendingPathComponent("Weekend.png")
            defer { try? FileManager.default.removeItem(at: url) }
            try image.pngData()!.write(to: url, options: .completeFileProtection)
            _ = try await self.repository.importFile(url, name: "Weekend.png", type: UTType.png.identifier)
            if ProcessInfo.processInfo.arguments.contains("-vault.batch") {
                let broken = await self.repository.temporary.appendingPathComponent("Broken.png")
                defer { try? FileManager.default.removeItem(at: broken) }
                try Data("not an image".utf8).write(to: broken, options: .completeFileProtection)
                _ = try await self.repository.importFile(broken, name: "Broken.png", type: UTType.png.identifier)
                let pdf = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
                let data = pdf.pdfData { context in
                    for text in ["BATCH FIRST PAGE 7429", "BATCH SECOND PAGE 8250"] {
                        context.beginPage()
                        (text as NSString).draw(at: CGPoint(x: 40, y: 150), withAttributes: [.font: UIFont.systemFont(ofSize: 28), .foregroundColor: UIColor.black])
                    }
                }
                let file = await self.repository.temporary.appendingPathComponent("Batch pages.pdf")
                defer { try? FileManager.default.removeItem(at: file) }
                try data.write(to: file, options: .completeFileProtection)
                _ = try await self.repository.importFile(file, name: "Batch pages.pdf", type: UTType.pdf.identifier)
                return
            }
            try await self.repository.addNote(name: "Travel checklist", text: "Passport\nBoarding pass\nSpare charger", folder: nil)
        }
    }
    #endif
}

enum BiometricKey {
    static let defaultsKey = "vault.biometricID"
    static var service: String { (Bundle.main.bundleIdentifier ?? "Vault") + ".keys" }
    static func base(_ id: UUID) -> [String: Any] { base(account: id.uuidString) }
    static func base(account: String) -> [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account] }
    /// Every vault account with an item in this app's service. Attributes only: no data is read,
    /// and no authentication prompt is shown.
    static func accounts() -> [String] {
        var query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecMatchLimit as String: kSecMatchLimitAll, kSecReturnAttributes as String: true]
        let context = LAContext(); context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = context
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let items = result as? [[String: Any]] else { return [] }
        return items.compactMap { $0[kSecAttrAccount as String] as? String }
    }
    static func remove(account: String) { SecItemDelete(base(account: account) as CFDictionary) }
    static func save(_ data: Data, id: UUID) throws {
        let context = LAContext(); var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else { throw error ?? NSError(domain: "Vault", code: 1, userInfo: [NSLocalizedDescriptionKey: String(localized: "Set up Face ID or Touch ID first.")]) }
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .biometryCurrentSet, nil) else { throw VaultError.Damaged }
        var query = base(id); query[kSecAttrAccessControl as String] = access; query[kSecValueData as String] = data
        remove(id: id); guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else { throw VaultError.Damaged }
    }
    static func remove(id: UUID) { SecItemDelete(base(id) as CFDictionary) }
    static func read(id: UUID) async throws -> Data {
        let reason = String(localized: "Open your encrypted vault")
        let context = LAContext(); context.localizedFallbackTitle = ""; context.localizedReason = reason
        _ = try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
        var query = base(id); query[kSecReturnData as String] = true; query[kSecUseAuthenticationContext as String] = context
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { throw VaultError.InvalidCredential }
        return data
    }
}
enum VaultGate {
    static let unlock = UnlockContent(appName: "Vault", features: [
        String(localized: "gate.unlock.files", defaultValue: "Encrypted photos, videos, files and notes"),
        String(localized: "gate.unlock.vaults", defaultValue: "Independent vaults and useful organization"),
        String(localized: "gate.unlock.backups", defaultValue: "Portable encrypted backups. No subscription.")])
    static let before = BeforeYouBuyContent(title: String(localized: "gate.byb.title", defaultValue: "Your iPhone already has a Hidden album."), paragraphs: [
        .init(String(localized: "gate.byb.p1", defaultValue: "Photos locks its Hidden album with Face ID, Touch ID or your passcode, Notes can lock notes with a password, and iOS can lock or hide eligible apps. If that’s enough, keep your money.")),
        .init(String(localized: "gate.byb.p2", defaultValue: "What this adds: each vault opens with its own passphrase, PIN or pattern, and keeps photos, videos, files and notes encrypted, organized and backed up in a portable file. Try it for 7 days, then {price}, once."),
              withoutPrice: String(localized: "gate.byb.p2.noPrice", defaultValue: "What this adds: each vault opens with its own passphrase, PIN or pattern, and keeps photos, videos, files and notes encrypted, organized and backed up in a portable file. Try it for 7 days, then one purchase.")),
        .init(String(localized: "gate.byb.p3", defaultValue: "What it can’t do: protect an open vault on a compromised iPhone, take back copies you’ve exported, or hide that Vault is installed. After the trial your files stay readable and exportable. Nobody can reset your secret for you."))],
        justScanLabel: String(localized: "gate.byb.lookAround", defaultValue: "Look around"))
    static let expired = TrialEndedContent(
        stillFree: [String(localized: "gate.free.files", defaultValue: "Open, organize, export and delete existing files"),
                    String(localized: "gate.free.backup", defaultValue: "Back up, restore and recover your vaults")],
        unlockKeeps: [String(localized: "gate.keeps.add", defaultValue: "Add vaults, files, photos and notes")])
}

extension VaultModel {
    func scheduleAutomaticBackup() {
        automaticTask?.cancel()
        guard let id = library?.id else { return }
        automaticNeedsPreparation = true
        automaticTask = Task {
            do {
                let status = try await automatic.status(target: id)
                if library?.id == id {
                    automaticStatus = status
                    if status != nil { let revision = try await repository.backupRevision(); automaticNeedsPreparation = try await automatic.needsPreparation(target: id, revision: revision) }
                }
                guard status != nil else { return }
                try await Task.sleep(for: .seconds(2))
                await prepareAutomaticBackup()
            } catch is CancellationError { } catch { if library?.id == id { automaticMessage = error.vaultMessage } }
        }
    }
    func enableAutomaticBackup(in directory: URL) async {
        guard let id = library?.id else { return }
        do { try await automatic.configure(target: id, directory: directory); automaticMessage = nil; scheduleAutomaticBackup() }
        catch { automaticMessage = error.vaultMessage }
    }
    func disableAutomaticBackup() async {
        guard let id = library?.id else { return }; automaticTask?.cancel()
        do { try await automatic.disable(target: id); automaticStatus = nil; automaticMessage = nil }
        catch { automaticMessage = error.vaultMessage }
    }
    func prepareAutomaticBackup() async {
        guard !automaticWorking, let id = library?.id else { return }
        if busy { scheduleAutomaticBackup(); return }
        automaticWorking = true
        var prepared = false
        defer { automaticWorking = false; if prepared && library != nil { scheduleAutomaticBackup() } }
        do {
            let revision = try await repository.backupRevision()
            guard try await automatic.needsPreparation(target: id, revision: revision) else { return }
            let root = await automatic.root.appendingPathComponent("preparing")
            try AutomaticBackup.protectDirectory(root)
            let output = root.appendingPathComponent(UUID().uuidString + ".vault7")
            defer { try? FileManager.default.removeItem(at: output) }
            let (actualID, actualRevision) = try await repository.automaticBackup(to: output)
            guard actualID == id else { return }
            // Once fully prepared, preserve the encrypted artifact even if the UI just locked.
            try await automatic.enqueue(source: output, target: id, revision: actualRevision, fileExtension: "vault7")
            prepared = true
            if library?.id == id { automaticStatus = try await automatic.status(target: id); automaticNeedsPreparation = false; automaticMessage = nil }
            BackupBackground.schedule()
            await retryAutomaticBackups()
        } catch {
            if library?.id == id { automaticMessage = String(localized: "Backup needs another attempt: \(error.vaultMessage)") }
        }
    }
    func retryAutomaticBackups() async {
        guard deliveryTask == nil else { return }
        deliveryTask = Task {
            defer { deliveryTask = nil }
            do {
                try await automatic.deliverPending()
                if let id = library?.id { automaticStatus = try await automatic.status(target: id) }
                if try await automatic.hasPending() { BackupBackground.schedule() }
            } catch {
                if library != nil { automaticMessage = error.vaultMessage }
                BackupBackground.schedule()
            }
        }
        await deliveryTask?.value
    }
}
