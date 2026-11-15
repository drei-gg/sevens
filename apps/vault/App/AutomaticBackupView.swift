import SwiftUI
import SevenKit
import SevenBackup
import BackgroundTasks

struct AutomaticBackupSection: View {
    @Environment(VaultModel.self) private var model
    @State private var choosing = false
    var body: some View {
        Section("Automatic encrypted backup") {
            if let status = model.automaticStatus {
                LabeledContent("Folder", value: status.destination)
                if model.automaticWorking { ProgressView("Preparing encrypted backup…") }
                if let date = status.copiedAt { Text("Last copied to provider: \(date.formatted()).").accessibilityIdentifier("vault.backupReceipt") }
                else { Text("No completed copy yet.") }
                if model.automaticNeedsPreparation { Text("Latest changes are waiting to be prepared.") }
                if status.pendingRevision != nil { Text("Encrypted backup queued for delivery.") }
                if let failure = status.failure { Text(failure).foregroundStyle(.red) }
                Button("Back up now") { Task { await model.prepareAutomaticBackup(); await model.retryAutomaticBackups() } }
                Button("Change backup folder") { choosing = true }
                Button("Turn off automatic backup", role: .destructive) { Task { await model.disableAutomaticBackup() } }
            } else { Button("Choose automatic backup folder") { choosing = true } }
            if let message = model.automaticMessage { Text(message).foregroundStyle(.red) }
            Text("Choose iCloud Drive or another folder in Files once. Changes are backed up while this vault is open. Completed encrypted backups can retry in the background; iOS decides when. If preparation is interrupted, open this vault again to catch up. The latest three copies made by this device are kept. Your provider handles cloud upload; a completed copy is not an upload receipt.").font(.footnote).foregroundStyle(.secondary)
            Text("Backups remain available after trial expiry. Turning this off leaves existing copies in your chosen folder.").font(.footnote).foregroundStyle(.secondary)
        }.sheet(isPresented: $choosing) { SevenBackupFolderPicker { directory in choosing = false; Task { await model.enableAutomaticBackup(in: directory) } } }
    }
}
@MainActor final class BackupAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BackupBackground.register(); return true
    }
}
@MainActor enum BackupBackground {
    static var queue: AutomaticBackup?
    static var identifier: String { (Bundle.main.bundleIdentifier ?? "org.example.sevens.vault") + ".backup" }
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            guard let task = task as? BGProcessingTask else { task.setTaskCompleted(success: false); return }
            let work = Task { @MainActor in
                guard let queue else { task.setTaskCompleted(success: false); return }
                do {
                    try await queue.deliverPending()
                    let pending = try await queue.hasPending()
                    task.setTaskCompleted(success: !pending)
                    if pending { schedule() }
                } catch { task.setTaskCompleted(success: false); schedule() }
            }
            task.expirationHandler = { work.cancel() }
        }
    }
    static func schedule() {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        // A pending request or disabled background refresh is not a failed backup. Foreground retries remain.
        try? BGTaskScheduler.shared.submit(request)
    }
}
