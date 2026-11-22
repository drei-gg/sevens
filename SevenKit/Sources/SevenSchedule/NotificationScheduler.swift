import Foundation
import SevenKitCore
import UserNotifications

/// The pending-notification queue, behind a protocol so the scheduler is testable without a host
/// app (`UNUserNotificationCenter.current()` needs one).
public protocol NotificationCenterClient: Sendable {
    /// Whether notifications may be delivered (authorized or provisional).
    func authorized() async -> Bool
    func requestAuthorization(_ options: UNAuthorizationOptions) async throws -> Bool
    func pendingIDs() async -> [String]
    func add(_ request: ScheduledNotification) async throws
    func removePending(_ ids: [String])
    func removeDelivered(_ ids: [String])
}

/// The real queue.
public struct LiveNotificationCenter: NotificationCenterClient {
    public init() {}
    private var center: UNUserNotificationCenter { .current() }
    public func authorized() async -> Bool {
        let status = await center.notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional
    }
    public func requestAuthorization(_ options: UNAuthorizationOptions) async throws -> Bool {
        try await center.requestAuthorization(options: options)
    }
    public func pendingIDs() async -> [String] { await center.pendingNotificationRequests().map(\.identifier) }
    public func add(_ request: ScheduledNotification) async throws {
        let content = UNMutableNotificationContent()
        content.title = request.title
        content.body = request.body
        if request.sound { content.sound = .default }
        if let category = request.category { content.categoryIdentifier = category }
        for (key, value) in request.userInfo { content.userInfo[key] = value }
        let trigger: UNNotificationTrigger
        switch request.trigger {
        case .at(let date):
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, date.timeIntervalSinceNow), repeats: false)
        case .wallClock(let parts, let repeats):
            trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: repeats)
        }
        try await center.add(UNNotificationRequest(identifier: request.id, content: content, trigger: trigger))
    }
    public func removePending(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        center.removePendingNotificationRequests(withIdentifiers: ids)
    }
    public func removeDelivered(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }
}

/// What a reconcile did; each app has its own words for it.
public struct NotificationOutcome: Sendable, Equatable {
    /// Whether notifications may be delivered. When not, the queue was left as it was (except that
    /// an empty schedule always clears it).
    public var allowed: Bool
    /// Requests now pending for the schedule.
    public var scheduled: Int
    /// Wanted requests left out because the queue is full; the soonest were kept.
    public var overLimit: Int
    /// Requests the system refused. When any failed, nothing else was removed.
    public var failed: Int

    public init(allowed: Bool, scheduled: Int, overLimit: Int, failed: Int) {
        self.allowed = allowed; self.scheduled = scheduled; self.overLimit = overLimit; self.failed = failed
    }
}

/// One app's local notifications (shared by Fast, Bloom and Alarm's quiet reminders, 2026-11-22):
/// - It only touches identifiers the app owns (`owns`), so another part of the app keeps its own.
/// - Reconciles run one at a time, so an older snapshot can never finish after a newer one.
/// - Wanted requests are added or replaced first; pending ones no longer wanted are removed, and
///   leftovers are cleared only once every add succeeded, so a failure never empties the queue.
/// - The queue's 64-request limit keeps the soonest (`NotificationPlanner`).
public actor NotificationScheduler {
    private let center: any NotificationCenterClient
    private let owns: @Sendable (String) -> Bool
    private let limit: Int
    private var tail: Task<NotificationOutcome, Never>?

    public init(
        center: any NotificationCenterClient = LiveNotificationCenter(),
        limit: Int = NotificationPlanner.systemLimit, owns: @escaping @Sendable (String) -> Bool
    ) {
        self.center = center; self.limit = limit; self.owns = owns
    }

    /// A scheduler owning every identifier that starts with `prefix`.
    public init(center: any NotificationCenterClient = LiveNotificationCenter(), limit: Int = NotificationPlanner.systemLimit, prefix: String) {
        self.init(center: center, limit: limit, owns: { $0.hasPrefix(prefix) })
    }

    public func authorized() async -> Bool { await center.authorized() }

    public func requestAuthorization(_ options: UNAuthorizationOptions) async throws -> Bool {
        try await center.requestAuthorization(options)
    }

    /// Makes the pending queue match `wanted`, and removes delivered alerts in `removeDelivered`
    /// (ones the user already acted on in the app).
    public func reconcile(_ wanted: [ScheduledNotification], removeDelivered: [String] = []) async -> NotificationOutcome {
        let previous = tail
        let task = Task { [center, owns, limit] in
            _ = await previous?.value
            return await Self.apply(wanted, removeDelivered: removeDelivered, center: center, owns: owns, limit: limit)
        }
        tail = task
        return await task.value
    }

    private static func apply(
        _ wanted: [ScheduledNotification], removeDelivered: [String], center: any NotificationCenterClient,
        owns: @Sendable (String) -> Bool, limit: Int
    ) async -> NotificationOutcome {
        let allowed = await center.authorized()
        let plan = NotificationPlanner.plan(wanted: wanted, pending: await center.pendingIDs(), owns: owns, limit: limit, now: Date())
        defer { center.removeDelivered(removeDelivered) }
        if wanted.isEmpty || !allowed {
            if wanted.isEmpty { center.removePending(plan.remove) }
            return NotificationOutcome(allowed: allowed, scheduled: 0, overLimit: 0, failed: 0)
        }
        center.removePending(plan.remove)
        var added = Set<String>()
        var failed = 0
        for request in plan.add {
            do { try await center.add(request); added.insert(request.id) } catch { failed += 1 }
        }
        if failed == 0 {
            center.removePending(await center.pendingIDs().filter { owns($0) && !added.contains($0) })
        }
        return NotificationOutcome(allowed: true, scheduled: added.count, overLimit: plan.overLimit, failed: failed)
    }

    // Single requests, for an app that keeps its own receipts (Alarm's quiet reminders).

    public func pendingIDs() async -> [String] { await center.pendingIDs().filter(owns) }
    public func add(_ request: ScheduledNotification) async throws { try await center.add(request) }
    public func remove(_ ids: [String]) { center.removePending(ids.filter(owns)) }
}
