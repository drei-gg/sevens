import Foundation
import Observation
import QRCore
import SevenKit
import SevenKitCore

// AppModel: the one object every screen reads. Create once at launch, put it in the
// environment with `.qrStore(model)` (AppModel+Gate.swift: this also installs SevenKit's
// `Store` and the unlock texts), and call `await model.start()` from a `.task` on the root.
//
//     @Environment(AppModel.self) private var model
//
// What it holds
//   store      SevenKit `Store`: trial state, price, purchase, restore. `store.paidFeaturesAvailable`
//              is the plain check; use `GatedButton` / `.gated(feature:)` for paid entry points.
//   settings   `AppSettings` (`keepHistory`).
//   history    the `HistoryStore` actor (see HistoryStore.swift for its API).
//   analyze    `@Sendable (ScannedCode) -> Analysis`, injectable. Call `await model.analysis(of:)`
//              so the work runs off the main actor.
//   pendingCode  set by the `-qr.inject` launch argument (WP 3.1 parses it); the scan screen
//              takes it with `takePendingCode()` and shows the result sheet.
//
// History for views (observable mirrors of the actor, refreshed on every change)
//   historyItems (newest first), historyIsReadOnly, historyReadOnlyReason, historyCount
//   canSaveHistory     keepHistory AND paid features available (trial or unlocked)
//   historyIsLocked    paid features NOT available: nothing new is saved, everything else works
//   record(_:kind:) / record(code:kind:)   save a scan or a made code; no-op unless canSaveHistory
//   searchHistory(_:), setNote(_:for:), deleteHistory(ids:), clearHistory(),
//   exportHistoryJSON(), exportHistoryCSV()    never gated (PLAN §6.9/§6.10, SPEC §13)
//
// Lifecycle: start(), sceneDidBecomeActive(), sceneDidEnterBackground() (flushes History).
//
// Peek: this model deliberately knows nothing about PeekModel; screens build it themselves
// (it is gated at the button with `GatedButton(feature:)`).
//
// Entitlement snapshot for the Make extension: SevenKit's `Store` writes it itself through
// `EntitlementSnapshotStore` on every state change, so nothing is done here beyond starting
// the store.

@Observable
@MainActor
public final class AppModel {
    public typealias Analyze = @Sendable (ScannedCode) -> Analysis

    public let store: Store
    public let settings: AppSettings
    @ObservationIgnored public let history: HistoryStore
    @ObservationIgnored public let analyze: Analyze

    /// A code injected at launch (`-qr.inject`); consumed by `takePendingCode()`.
    public var pendingCode: ScannedCode?

    public private(set) var historyItems: [HistoryItem] = []
    public private(set) var historyIsReadOnly = false
    public private(set) var historyReadOnlyReason: String?

    @ObservationIgnored private let clock: @Sendable () -> Date
    @ObservationIgnored private var started = false
    @ObservationIgnored private var historyObserver: Task<Void, Never>?

    public init(
        store: Store,
        history: HistoryStore,
        settings: AppSettings = AppSettings(),
        analyze: Analyze? = nil,
        clock: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.store = store
        self.history = history
        self.settings = settings
        self.analyze = analyze ?? AppModel.defaultAnalyze
        self.clock = clock
    }

    /// The production setup: StoreKit store from the Info.plist config, History in
    /// Application Support, real analyzer.
    public convenience init(bundle: Bundle = .main) {
        let config = SevenConfig(bundle: bundle)
            ?? SevenConfig(trialProductID: "qr.trial7", unlockProductID: "qr.unlock")
        self.init(store: Store(config: config), history: HistoryStore(directory: HistoryStore.defaultDirectory()))
    }

    public static let defaultAnalyze: Analyze = { code in
        Analyzer(data: BundledData.shared).analyze(code)
    }

    // MARK: Lifecycle

    /// Warms `BundledData` on a background task, loads History, starts watching it, starts the
    /// store (prices, entitlements, update listener). Safe to call twice.
    public func start() async {
        guard !started else { return }
        started = true
        Task.detached(priority: .utility) { _ = BundledData.shared }
        await observeHistory()
        await store.start()
    }

    /// Call when the scene becomes active: re-evaluates the trial against the clock.
    public func sceneDidBecomeActive() async { await store.refresh() }

    /// Call when the scene goes to the background: writes any pending History change now.
    public func sceneDidEnterBackground() async { await history.flush() }

    // MARK: Analysis

    /// Runs `analyze` off the main actor.
    public func analysis(of code: ScannedCode) async -> Analysis {
        let analyze = analyze
        return await Task.detached(priority: .userInitiated) { analyze(code) }.value
    }

    public func takePendingCode() -> ScannedCode? {
        defer { pendingCode = nil }
        return pendingCode
    }

    // MARK: History: saving (gated)

    /// "Keep history" is on.
    public var keepsHistory: Bool { settings.keepHistory }
    /// Paid features are not available (trial over, or never started): nothing new is saved.
    public var historyIsLocked: Bool { !store.paidFeaturesAvailable }
    /// New scans and made codes are saved: Keep history on AND (trial or unlocked).
    public var canSaveHistory: Bool { settings.keepHistory && store.paidFeaturesAvailable }
    public var historyCount: Int { historyItems.count }

    /// Saves a scanned or made code. Returns the saved item, or nil when nothing was saved
    /// (Keep history off, locked state, or the saved file is from a newer version).
    ///
    /// Gating, in one place: only *saving new items* needs the trial or the unlock. Everything
    /// below (reading, search, export, delete, notes on existing items) works in any state, so
    /// what was saved during the trial stays readable and exportable (SPEC §13).
    /// Note: before `start()` finishes the store reads as not started, so call after launch.
    @discardableResult
    public func record(_ analysis: Analysis, kind: HistoryItem.Kind) async -> HistoryItem? {
        guard canSaveHistory else { return nil }
        let item = HistoryItem(analysis: analysis, kind: kind, date: clock())
        guard await history.add(item) else { return nil }
        return item
    }

    /// Analyzes `code` (off the main actor) and saves it. Use for made codes.
    @discardableResult
    public func record(code: ScannedCode, kind: HistoryItem.Kind) async -> HistoryItem? {
        guard canSaveHistory else { return nil }
        return await record(await analysis(of: code), kind: kind)
    }

    // MARK: History: never gated

    public func searchHistory(_ query: String) async -> [HistoryItem] { await history.search(query) }

    @discardableResult
    public func setNote(_ note: String?, for id: UUID) async -> Bool { await history.update(note: note, for: id) }

    @discardableResult
    public func deleteHistory(ids: Set<UUID>) async -> Int { await history.delete(ids: ids) }

    /// The caller shows the confirmation. Does nothing while the saved file is unreadable.
    public func clearHistory() async { await history.clear() }

    public func exportHistoryJSON() async throws -> Data { try await history.exportJSON() }
    public func exportHistoryCSV() async -> String { await history.exportCSV() }

    /// Sets the mirror without waiting for the actor (previews).
    func seedHistoryMirror(_ items: [HistoryItem]) { historyItems = items }

    /// Re-reads the mirrors. Called by the observer on every change; tests call it directly.
    public func refreshHistory() async {
        let state = await history.state()
        historyItems = state.items
        historyIsReadOnly = state.isReadOnly
        historyReadOnlyReason = state.readOnlyReason
    }

    private func observeHistory() async {
        await refreshHistory()
        historyObserver?.cancel()
        let changes = await history.changes()
        historyObserver = Task { [weak self] in
            for await _ in changes {
                guard let self else { return }
                await self.refreshHistory()
            }
        }
    }
}
