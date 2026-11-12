import Foundation
import Observation
import SevenKitCore

/// The one object the app and its views observe. Create it once at launch, call `start()`, put
/// it in the environment with `.sevenStore(_:unlock:)`.
@Observable
@MainActor
public final class Store {
    // MARK: Observable state
    public private(set) var state: EntitlementState = .notStarted
    public private(set) var facts = EntitlementFacts()
    /// `Product.displayPrice` of the unlock; nil until (unless) the products load.
    public private(set) var unlockDisplayPrice: String?
    public private(set) var trialDisplayPrice: String?
    /// True once `start()` has finished its first pass (products and entitlements asked for).
    public private(set) var hasStarted = false
    public private(set) var isBusy = false
    /// A short, user-facing line after a failed purchase or restore. Cleared by the next action.
    public private(set) var lastError: String?
    /// Debug only: the facts come from `LocalSource`, not StoreKit.
    public private(set) var isUsingLocalSource = false

    // MARK: Configuration
    @ObservationIgnored public let config: SevenConfig
    @ObservationIgnored public let trialPolicy: TrialPolicy
    @ObservationIgnored private(set) var source: any EntitlementSource
    @ObservationIgnored private let trialStartStore: any TrialStartStore
    @ObservationIgnored private let snapshots: EntitlementSnapshotStore?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let clock: @Sendable () -> Date
    @ObservationIgnored private var listener: Task<Void, Never>?
    @ObservationIgnored private var expiryTimer: Task<Void, Never>?
    @ObservationIgnored private var lastWritten: EntitlementState?
    @ObservationIgnored private var firstLaunchDate: Date?
    @ObservationIgnored private var started = false

    /// - Parameters:
    ///   - source: nil means `StoreKitSource(config:)`.
    ///   - trialStartStore: nil means a Keychain store named after the unlock product ID.
    ///   - defaults: where the shown-once flags and the Debug local source live.
    public init(
        config: SevenConfig,
        trialPolicy: TrialPolicy = .onPurchase,
        source: (any EntitlementSource)? = nil,
        trialStartStore: (any TrialStartStore)? = nil,
        defaults: UserDefaults = .standard,
        clock: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.config = config
        self.trialPolicy = trialPolicy
        self.source = source ?? DevUnlock.source() ?? StoreKitSource(config: config)
        self.trialStartStore = trialStartStore ?? KeychainTrialStartStore(service: config.productIDs.unlock)
        self.snapshots = EntitlementSnapshotStore(appGroup: config.appGroup)
        self.defaults = defaults
        self.clock = clock
    }

    // MARK: Derived

    public var paidFeaturesAvailable: Bool { state.paidFeaturesAvailable }
    public var trialStartedAt: Date? { effectiveFacts.trialStartedAt }
    /// 1-based day of the running trial, nil outside it.
    public var trialDay: Int? {
        guard case .trial = state, let start = trialStartedAt else { return nil }
        return TrialMath.dayNumber(startedAt: start, now: clock(), trialLength: config.trialLength)
    }
    public var trialDaysTotal: Int { TrialMath.totalDays(config.trialLength) }
    /// The unlock can be bought: a price is known (Debug local source counts).
    public var canPurchase: Bool { unlockDisplayPrice != nil && !isBusy && state != .pending && state != .unlocked }
    public var shouldPresentTrialEnded: Bool {
        if case .expired = state { return !SevenFlags.trialEndedShown(defaults) }
        return false
    }
    public var shouldPresentBeforeYouBuy: Bool { !SevenFlags.beforeYouBuyShown(defaults) }
    /// The one sheet that may open at launch: Before you buy first, the trial-ended sheet only
    /// when Before you buy isn't due (SPEC §13).
    public var launchSheet: LaunchSheetPlan {
        LaunchSheetPlan.next(beforeYouBuyDue: shouldPresentBeforeYouBuy, trialEndedDue: shouldPresentTrialEnded)
    }

    private var effectiveFacts: EntitlementFacts {
        var f = facts
        if trialPolicy == .onFirstLaunch, f.trialStartedAt == nil { f.trialStartedAt = firstLaunchDate }
        return f
    }

    // MARK: Lifecycle

    /// Launch: price lookup, current entitlements, then the update listener. Safe to call twice.
    public func start() async {
        guard !started else { return }
        started = true
        if trialPolicy == .onFirstLaunch { firstLaunchDate = trialStartStore.writeOnce(clock()) }

        var prices = await source.loadPrices()
        #if DEBUG
        if prices == nil, !(source is LocalSource) {
            source = LocalSource(defaults: defaults, clock: clock)
            isUsingLocalSource = true
            prices = await source.loadPrices()
        }
        #endif
        unlockDisplayPrice = prices?.unlock
        trialDisplayPrice = prices?.trial
        facts = await source.currentFacts()
        recompute()
        listen()
        hasStarted = true
    }

    /// Re-evaluates the state against the clock (call when the app becomes active).
    public func refresh() async {
        facts = await source.currentFacts()
        recompute()
    }

    private func listen() {
        listener?.cancel()
        let updates = source.factUpdates()
        listener = Task { [weak self] in
            for await newFacts in updates {
                guard let self else { return }
                self.facts = newFacts
                self.recompute()
            }
        }
    }

    // MARK: Actions

    /// "Start my free 7 days". With `.onFirstLaunch` the trial already runs; this does nothing.
    public func startTrial() async {
        guard trialPolicy == .onPurchase, state == .notStarted else { return }
        await perform(.trial)
    }

    public func unlock() async {
        guard state != .unlocked else { return }
        await perform(.unlock)
    }

    public func restore() async {
        lastError = nil
        isBusy = true
        defer { isBusy = false }
        do {
            try await source.restore()
            facts = await source.currentFacts()
            recompute()
        } catch {
            lastError = seven("seven.error.restore", "Couldn't restore purchases. Check your connection and try again.")
        }
    }

    private enum Purchase { case trial, unlock }

    private func perform(_ purchase: Purchase) async {
        lastError = nil
        isBusy = true
        defer { isBusy = false }
        do {
            switch purchase {
            case .trial: _ = try await source.purchaseTrial()
            case .unlock: _ = try await source.purchaseUnlock()
            }
            facts = await source.currentFacts()
            recompute()
        } catch {
            lastError = seven("seven.error.purchase", "The purchase didn't go through. Try again in a moment.")
        }
    }

    /// Records that the Before-you-buy screen was shown.
    public func markBeforeYouBuyShown() { SevenFlags.markBeforeYouBuyShown(defaults) }
    public func markTrialEndedShown() { SevenFlags.markTrialEndedShown(defaults) }

    // MARK: State

    func apply(facts newFacts: EntitlementFacts) {
        facts = newFacts
        recompute()
    }

    private func recompute() {
        let now = clock()
        let newState = entitlementState(effectiveFacts, now: now, trialLength: config.trialLength)
        if newState != state { state = newState }
        if lastWritten != newState {
            snapshots?.write(EntitlementSnapshot(newState, writtenAt: now))
            lastWritten = newState
        }
        scheduleExpiry(newState, now: now)
    }

    private func scheduleExpiry(_ current: EntitlementState, now: Date) {
        expiryTimer?.cancel()
        expiryTimer = nil
        guard case .trial(let end) = current else { return }
        let wait = max(0.5, end.timeIntervalSince(now))
        expiryTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            guard !Task.isCancelled else { return }
            self?.recompute()
        }
    }

    #if DEBUG
    /// Debug panel: apply a preset (switches to the local source first if needed).
    public func applyDebugPreset(_ preset: LocalSource.Preset) {
        let local = useLocalSource()
        local.apply(preset)
        apply(facts: local.facts)
    }

    @discardableResult
    public func useLocalSource() -> LocalSource {
        if let local = source as? LocalSource { return local }
        let local = LocalSource(defaults: defaults, clock: clock)
        source = local
        isUsingLocalSource = true
        unlockDisplayPrice = "Test price"
        trialDisplayPrice = "Free"
        listen()
        return local
    }
    #endif
}
