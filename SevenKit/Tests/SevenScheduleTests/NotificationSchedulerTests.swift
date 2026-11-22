import Foundation
import Testing
import SevenKitCore
import SevenSchedule
import UserNotifications

/// An in-memory pending queue that can refuse adds.
final class FakeCenter: NotificationCenterClient, @unchecked Sendable {
    private let lock = NSLock()
    private var _pending: [String: ScheduledNotification] = [:]
    private var _delivered: Set<String> = []
    var allowed = true
    var refuse: Set<String> = []
    var adds = 0

    var pending: [String: ScheduledNotification] { lock.withLock { _pending } }
    var delivered: Set<String> { lock.withLock { _delivered } }
    func seed(_ ids: [String]) { lock.withLock { for id in ids { _pending[id] = ScheduledNotification(id: id, title: "", body: "", trigger: .at(.distantFuture)) } } }
    func deliver(_ ids: [String]) { lock.withLock { _delivered.formUnion(ids) } }

    func authorized() async -> Bool { allowed }
    func requestAuthorization(_ options: UNAuthorizationOptions) async throws -> Bool { allowed }
    func pendingIDs() async -> [String] { lock.withLock { Array(_pending.keys).sorted() } }
    func add(_ request: ScheduledNotification) async throws {
        if refuse.contains(request.id) { throw CocoaError(.featureUnsupported) }
        lock.withLock { adds += 1; _pending[request.id] = request }
    }
    func removePending(_ ids: [String]) { lock.withLock { for id in ids { _pending[id] = nil } } }
    func removeDelivered(_ ids: [String]) { lock.withLock { _delivered.subtract(ids) } }
}

@Suite struct NotificationSchedulerTests {
    func request(_ id: String, in seconds: TimeInterval = 3_600) -> ScheduledNotification {
        ScheduledNotification(id: id, title: "t", body: "b", trigger: .at(Date().addingTimeInterval(seconds)))
    }

    @Test func reconcileReplacesInPlaceAndRemovesWhatIsNoLongerWanted() async {
        let center = FakeCenter(); center.seed(["app.a", "app.old", "other.keep"])
        let scheduler = NotificationScheduler(center: center, prefix: "app.")
        let outcome = await scheduler.reconcile([request("app.a"), request("app.b")])
        #expect(outcome == NotificationOutcome(allowed: true, scheduled: 2, overLimit: 0, failed: 0))
        #expect(Set(center.pending.keys) == ["app.a", "app.b", "other.keep"])
    }

    @Test func aFailedAddNeverEmptiesTheQueue() async {
        let center = FakeCenter(); center.seed(["app.a", "app.b"])
        center.refuse = ["app.b"]
        let scheduler = NotificationScheduler(center: center, prefix: "app.")
        let outcome = await scheduler.reconcile([request("app.a"), request("app.b")])
        #expect(outcome.failed == 1)
        // The earlier app.b is still pending: a refused replacement does not lose it.
        #expect(Set(center.pending.keys) == ["app.a", "app.b"])
    }

    @Test func withoutPermissionTheQueueIsLeftAlone() async {
        let center = FakeCenter(); center.seed(["app.a"]); center.allowed = false
        let scheduler = NotificationScheduler(center: center, prefix: "app.")
        let outcome = await scheduler.reconcile([request("app.b")])
        #expect(!outcome.allowed)
        #expect(Set(center.pending.keys) == ["app.a"])
        // An empty schedule still clears it.
        _ = await scheduler.reconcile([])
        #expect(center.pending.isEmpty)
    }

    @Test func theLimitIsHonestAndKeepsTheSoonest() async {
        let center = FakeCenter()
        let scheduler = NotificationScheduler(center: center, limit: 3, prefix: "app.")
        let wanted = (1...5).map { request("app.\($0)", in: TimeInterval($0) * 60) }
        let outcome = await scheduler.reconcile(wanted)
        #expect(outcome.scheduled == 3 && outcome.overLimit == 2)
        #expect(Set(center.pending.keys) == ["app.1", "app.2", "app.3"])
    }

    @Test func deliveredAlertsTheUserActedOnAreRemoved() async {
        let center = FakeCenter(); center.deliver(["app.dose.1", "app.dose.2"])
        let scheduler = NotificationScheduler(center: center, prefix: "app.")
        _ = await scheduler.reconcile([request("app.next")], removeDelivered: ["app.dose.1"])
        #expect(center.delivered == ["app.dose.2"])
    }

    @Test func reconcilesRunOneAtATimeInOrder() async {
        let center = FakeCenter()
        let scheduler = NotificationScheduler(center: center, prefix: "app.")
        async let first = scheduler.reconcile([request("app.old")])
        async let second = scheduler.reconcile([request("app.new")])
        _ = await (first, second)
        let final = await scheduler.reconcile([request("app.last")])
        #expect(final.scheduled == 1)
        #expect(Set(center.pending.keys) == ["app.last"])
    }

    @Test func singleRequestsOnlyTouchOwnedIdentifiers() async throws {
        let center = FakeCenter(); center.seed(["other.x"])
        let scheduler = NotificationScheduler(center: center, owns: { UUID(uuidString: $0) != nil })
        let id = UUID().uuidString
        try await scheduler.add(request(id))
        #expect(await scheduler.pendingIDs() == [id])
        await scheduler.remove([id, "other.x"])
        #expect(Set(center.pending.keys) == ["other.x"])
    }
}
