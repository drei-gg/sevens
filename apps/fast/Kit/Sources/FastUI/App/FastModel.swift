import Foundation
import Observation
import SevenKit
import FastCore
import SevenCompanion

@Observable @MainActor
public final class FastModel {
    public let store: Store
    public internal(set) var archive = FastArchive()
    public private(set) var isReady = false
    public internal(set) var busy = false
    public var message: String?
    public var loadFailure: String?
    public var notificationStatus = ""
    @ObservationIgnored let repository: ArchiveStore
    @ObservationIgnored let reminders = ReminderService()
    @ObservationIgnored let surfaces = TimerSurfaces()
    @ObservationIgnored let companion = CompanionSession()
    @ObservationIgnored var watchStarted = false
    @ObservationIgnored private var started = false
    @ObservationIgnored private let reconcile: (@MainActor (FastArchive) async -> String)?

    /// System integrations are injected in package tests, which have no application identity.
    public init(store: Store, repository: ArchiveStore, reconcile: (@MainActor (FastArchive) async -> String)? = nil) {
        self.store = store; self.repository = repository; self.reconcile = reconcile
    }
    public convenience init(bundle: Bundle = .main) {
        let config = SevenConfig(bundle: bundle) ?? SevenConfig(trialProductID: "fast.trial7", unlockProductID: "fast.unlock")
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-fast.demo") || args.contains("-fast.ui") {
            let defaults = UserDefaults(suiteName: "FastTracker.UITests")!
            if args.contains("-fast.reset") { defaults.removePersistentDomain(forName: "FastTracker.UITests") }
            let source = LocalSource(defaults: defaults)
            source.apply(args.contains("-fast.expired") ? .expired : args.contains("-fast.fresh") ? .notStarted : .unlocked)
            let store = Store(config: config, source: source, defaults: defaults)
            // The screens tour passes -fast.launchSheets to see Before you buy and Trial ended at launch.
            if !args.contains("-fast.launchSheets") { store.markBeforeYouBuyShown(); store.markTrialEndedShown() }
            let file = base.appendingPathComponent("FastTracker/uitest.json")
            if args.contains("-fast.reset") { try? FileManager.default.removeItem(at: file) }
            self.init(store: store, repository: args.contains("-fast.demo")
                ? ArchiveStore(file: nil, seed: DemoArchive.make()) : ArchiveStore(file: file))
            return
        }
        #endif
        self.init(store: Store(config: config), repository: ArchiveStore(file: base.appendingPathComponent("FastTracker/records.v1.json")))
    }
    public func start() async {
        guard !started else { return }; started = true
        if reconcile == nil { startWatch() }
        do { archive = try await repository.read(); isReady = true } catch { loadFailure = FastFormat.error(error) }
        await store.start()
        await updateServices()
    }
    public func resume() async {
        if !started { await start(); return }
        guard store.hasStarted else { return }
        await store.refresh()
        if !isReady { await retryLoad() }
        await updateServices()
    }
    public func retryLoad() async {
        do { archive = try await repository.retryRead(); isReady = true; loadFailure = nil }
        catch { loadFailure = FastFormat.error(error) }
    }
    @discardableResult
    func change(_ action: @escaping @Sendable (inout FastArchive) throws -> Void) async -> Bool {
        guard isReady, !busy else { return false }
        busy = true
        defer { busy = false }
        do {
            archive = try await repository.transact(action)
            await updateServices()
            return true
        } catch { message = FastFormat.error(error); return false }
    }
    func saveFast(_ fast: FastRecord) async -> Bool {
        guard allowNewRecord(existing: archive.fasts.contains(where: { $0.id == fast.id })) else { return false }
        let now = Date()
        return await change { try $0.save(fast, now: now) }
    }
    func saveJournal(_ entry: JournalEntry) async -> Bool {
        guard allowNewRecord(existing: archive.journal.contains(where: { $0.id == entry.id })) else { return false }
        return await change { $0.journal.removeAll { $0.id == entry.id }; $0.journal.append(entry) }
    }
    func saveMetric(_ entry: MetricEntry) async -> Bool {
        guard allowNewRecord(existing: archive.metrics.contains(where: { $0.id == entry.id })) else { return false }
        return await change { $0.metrics.removeAll { $0.id == entry.id }; $0.metrics.append(entry) }
    }
    func importBackup(_ data: Data) async -> Bool {
        guard !busy, isReady else { return false }; busy = true
        defer { busy = false }
        do { archive = try await repository.importData(data); await updateServices(); return true }
        catch { message = FastFormat.error(error); return false }
    }
    func allowNewRecord(existing: Bool) -> Bool {
        guard existing || store.paidFeaturesAvailable else {
            message = f("The trial has ended or has not started. Open Settings to start a trial or unlock new records. Your existing records remain available.")
            return false
        }
        return true
    }
    func updateServices() async {
        guard isReady else { return }
        if let reconcile {
            notificationStatus = await reconcile(archive)
        } else {
            notificationStatus = await reminders.update(archive)
            await surfaces.update(archive)
            startWatch()
            publishWatch()
        }
    }
}
