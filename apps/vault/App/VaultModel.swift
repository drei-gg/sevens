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

struct PresentedFiles: Identifiable { let id = UUID(); let urls: [URL]; var sharing = false; var filesExport = false }
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
    var presentation: PresentedFiles?
    var generation = 0
    var importResult = ""
    private var started = false
    private var operation = 0
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
        automaticTask?.cancel(); automaticTask = nil; automaticStatus = nil; automaticMessage = nil
        generation += 1; operation += 1; library = nil; recoveryKey = nil; presentation = nil; error = nil; importResult = ""
        // Stay busy until the actor has discarded keys and any in-flight operation has finished.
        busy = true; progress = "Locking…"
        let token = operation
        Task { await repository.lock(); if token == operation { busy = false; progress = "" }; await retryAutomaticBackups() }
    }
    func create(name: String, passphrase: String, kind: VaultCredentialKind = .passphrase) async {
        guard store.paidFeaturesAvailable else { error = "Start the trial or unlock to add a vault."; return }
        await perform("Creating encrypted vault…") {
            let result = try await self.repository.create(name: name, passphrase: passphrase, kind: kind)
            return (result.library, result.recoveryKey)
        }
    }
    func unlock(_ credential: String, recovery: Bool, kind: VaultCredentialKind = .passphrase) async {
        await perform("Opening vault…") { (try await self.repository.unlock(credential: credential, recovery: recovery, kind: kind), nil) }
    }
    private func perform(_ label: String, action: () async throws -> (VaultLibrary, String?)) async {
        guard !busy else { return }; busy = true; progress = label; operation += 1; let token = operation
        defer { if token == operation { busy = false; progress = "" } }
        do { let (value, recovery) = try await action(); guard token == operation else { return }; library = value; recoveryKey = recovery; scheduleAutomaticBackup() }
        catch { if token == operation { self.error = error.vaultMessage } }
    }
    func update(_ label: String = "Saving…", action: () async throws -> Void) async {
        guard !busy, library != nil else { return }; busy = true; progress = label; let token = operation
        defer { if token == operation { busy = false; progress = "" } }
        do { try await action(); let value = try await repository.snapshot(); if token == operation { library = value; scheduleAutomaticBackup() } }
        catch { if token == operation { self.error = error.vaultMessage } }
    }
    func importFiles(_ urls: [URL], folder: UUID?) async {
        guard !busy, library != nil, store.paidFeaturesAvailable else { return }
        busy = true; let token = operation; var successes = 0; var failures: [String] = []
        defer { if token == operation { busy = false; progress = "" } }
        for (index, url) in urls.enumerated() {
            guard token == operation else { return }
            progress = "Encrypting \(index + 1) of \(urls.count)…"
            let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let type = (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType?.identifier) ?? "public.data"
                _ = try await repository.importFile(url, name: url.lastPathComponent, type: type, folder: folder); successes += 1
            } catch { failures.append("\(url.lastPathComponent): \(error.vaultMessage)") }
        }
        guard token == operation else { return }
        library = try? await repository.snapshot(); scheduleAutomaticBackup()
        importResult = "\(successes) imported. Originals stay where they were."
        if !failures.isEmpty { error = "\(failures.count) could not be imported:\n" + failures.joined(separator: "\n") }
    }
    func importPhotos(_ items: [PhotosPickerItem], folder: UUID?) async {
        guard !busy, library != nil, store.paidFeaturesAvailable else { return }
        busy = true; let token = operation; var successes = 0; var failures: [String] = []
        defer { if token == operation { busy = false; progress = "" } }
        for (index, item) in items.enumerated() {
            guard token == operation else { return }
            progress = "Loading and encrypting \(index + 1) of \(items.count)…"
            do {
                guard let media = try await item.loadTransferable(type: IncomingMedia.self) else { throw VaultError.Unsupported }
                defer { try? FileManager.default.removeItem(at: media.url.deletingLastPathComponent()) }
                guard token == operation else { return }
                let type = (try? media.url.resourceValues(forKeys: [.contentTypeKey]).contentType?.identifier) ?? "public.data"
                _ = try await repository.importFile(media.url, name: media.url.lastPathComponent, type: type, folder: folder); successes += 1
            } catch { failures.append("Item \(index + 1): \(error.vaultMessage)") }
        }
        guard token == operation else { return }
        library = try? await repository.snapshot(); scheduleAutomaticBackup(); importResult = "\(successes) imported. Originals stay in Photos."
        if !failures.isEmpty { error = "\(failures.count) imports failed:\n" + failures.joined(separator: "\n") }
    }
    func preview(_ item: VaultItem) async {
        guard !busy else { return }; let token = operation
        await update("Preparing preview…") {
            let url = try await self.repository.export(item.id)
            if token == self.operation { self.presentation = PresentedFiles(urls: [url]) }
        }
    }
    func export(_ ids: Set<UUID>) async {
        guard !ids.isEmpty else { return }; let token = operation
        await update("Preparing originals…") {
            var urls: [URL] = []; var failures: [String] = []
            for id in ids.sorted(by: { $0.uuidString < $1.uuidString }) {
                guard token == self.operation else { return }
                do { urls.append(try await self.repository.export(id)) } catch { failures.append(error.vaultMessage) }
            }
            guard token == self.operation else { return }
            if !urls.isEmpty { self.presentation = PresentedFiles(urls: urls, sharing: true) }
            if !failures.isEmpty { self.error = "\(failures.count) exports failed. Successful originals are ready.\n" + failures.joined(separator: "\n") }
        }
    }
    func backup() async {
        let token = operation
        await update("Verifying and preparing encrypted backup…") {
            let url = try await self.repository.backup()
            if token == self.operation { self.presentation = PresentedFiles(urls: [url], sharing: true, filesExport: true) }
        }
    }
    func restore(_ url: URL, credential: String, recovery: Bool, kind: VaultCredentialKind = .passphrase) async {
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        await perform("Verifying backup…") { (try await self.repository.restore(url, credential: credential, recovery: recovery, kind: kind), nil) }
    }
    func cleanPresentation() { presentation = nil; Task { try? await repository.clearTemporary() } }
    func enableBiometrics() async {
        await update {
            let (id, data) = try await self.repository.biometricMaterial()
            try BiometricKey.save(data, id: id)
            UserDefaults.standard.set(id.uuidString, forKey: "vault.biometricID")
        }
    }
    func replaceRecoveryKey() async {
        let token = operation
        await update("Replacing recovery key…") {
            let replacement = try await self.repository.replaceRecoveryKey()
            if token == self.operation { self.recoveryKey = replacement }
        }
    }
    func disableBiometrics() {
        if let id = library?.id { BiometricKey.remove(id: id) }
        UserDefaults.standard.removeObject(forKey: "vault.biometricID")
    }
    var hasBiometricKey: Bool { UserDefaults.standard.string(forKey: "vault.biometricID") != nil }
    func unlockBiometrics() async {
        guard let text = UserDefaults.standard.string(forKey: "vault.biometricID"), let id = UUID(uuidString: text) else { return }
        await perform("Authenticating…") {
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
        await update("Importing sample originals…") {
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: 640, height: 480))
            let image = renderer.image { context in
                UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: 640, height: 480))
                UIColor.systemYellow.setFill(); context.cgContext.fillEllipse(in: CGRect(x: 440, y: 50, width: 100, height: 100))
                UIColor.systemIndigo.setFill(); let path = UIBezierPath(); path.move(to: CGPoint(x: 0, y: 480)); path.addLine(to: CGPoint(x: 240, y: 170)); path.addLine(to: CGPoint(x: 640, y: 480)); path.fill()
            }
            let url = await self.repository.temporary.appendingPathComponent("Weekend.png")
            defer { try? FileManager.default.removeItem(at: url) }
            try image.pngData()!.write(to: url, options: .completeFileProtection)
            _ = try await self.repository.importFile(url, name: "Weekend.png", type: UTType.png.identifier)
            try await self.repository.addNote(name: "Travel checklist", text: "Passport\nBoarding pass\nSpare charger", folder: nil)
        }
    }
    #endif
}

enum BiometricKey {
    static func base(_ id: UUID) -> [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: (Bundle.main.bundleIdentifier ?? "Vault") + ".keys", kSecAttrAccount as String: id.uuidString] }
    static func save(_ data: Data, id: UUID) throws {
        let context = LAContext(); var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else { throw error ?? NSError(domain: "Vault", code: 1, userInfo: [NSLocalizedDescriptionKey: "Set up Face ID or Touch ID first."]) }
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .biometryCurrentSet, nil) else { throw VaultError.Damaged }
        var query = base(id); query[kSecAttrAccessControl as String] = access; query[kSecValueData as String] = data
        remove(id: id); guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else { throw VaultError.Damaged }
    }
    static func remove(id: UUID) { SecItemDelete(base(id) as CFDictionary) }
    static func read(id: UUID) async throws -> Data {
        let context = LAContext(); context.localizedFallbackTitle = ""; context.localizedReason = "Open your encrypted vault"
        _ = try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Open your encrypted vault")
        var query = base(id); query[kSecReturnData as String] = true; query[kSecUseAuthenticationContext as String] = context
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { throw VaultError.InvalidCredential }
        return data
    }
}
enum VaultGate {
    static let unlock = UnlockContent(appName: "Vault", features: ["Encrypted photos, videos, files and notes", "Independent vaults and useful organization", "Portable encrypted backups. No subscription."])
    static let before = BeforeYouBuyContent(title: "Your phone already has a Hidden album.", paragraphs: [
        .init("Photos locks its Hidden album with Face ID, Touch ID or your device passcode. Notes can also use a separate password. If those are enough, keep your money."),
        .init("Vault adds an independent passphrase, an encrypted library for mixed files, organization and portable backups. Try it for 7 days, then {price}, once.", withoutPrice: "Vault adds an independent passphrase, an encrypted library for mixed files, organization and portable backups. Try it for 7 days, then one purchase."),
        .init("Existing files remain readable and exportable after the trial. Recovery needs your credentials and an intact vault or backup. We cannot reset your encryption key.")], justScanLabel: "Look around")
    static let expired = TrialEndedContent(stillFree: ["Open, organize, export and delete existing files", "Back up, restore and recover your vaults"], unlockKeeps: ["Add vaults, files, photos and notes"])
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
            if library?.id == id { automaticMessage = "Backup needs another attempt: " + error.vaultMessage }
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
