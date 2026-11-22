import Foundation
import Testing
import SevenKitCore

@Suite struct NotificationPlanTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    var utc: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }

    func at(_ id: String, _ seconds: TimeInterval) -> ScheduledNotification {
        ScheduledNotification(id: id, title: "t", body: "b", trigger: .at(now.addingTimeInterval(seconds)))
    }

    func weekly(_ id: String) -> ScheduledNotification {
        var parts = DateComponents(); parts.weekday = 2; parts.hour = 7; parts.minute = 0
        return ScheduledNotification(id: id, title: "t", body: "b", trigger: .wallClock(parts, repeats: true))
    }

    @Test func onlyOwnedLeftoversAreRemoved() {
        let plan = NotificationPlanner.plan(
            wanted: [at("app.a", 60)], pending: ["app.a", "app.old", "other.x"],
            owns: { $0.hasPrefix("app.") }, now: now, calendar: utc)
        #expect(plan.remove == ["app.old"])
        #expect(plan.add.map(\.id) == ["app.a"])
    }

    @Test func pastOneOffsAreSkipped() {
        let plan = NotificationPlanner.plan(
            wanted: [at("app.gone", -1), at("app.now", 0), at("app.soon", 1)], pending: [],
            owns: { _ in true }, now: now, calendar: utc)
        #expect(plan.past == 2)
        #expect(plan.add.map(\.id) == ["app.soon"])
    }

    @Test func theLimitKeepsRepeatingThenTheSoonest() {
        var floating = DateComponents(); floating.year = 2027; floating.month = 2; floating.day = 15; floating.hour = 8; floating.minute = 0
        let wanted = [
            at("app.late", 9_000), at("app.early", 60), weekly("app.weekly"),
            ScheduledNotification(id: "app.floating", title: "t", body: "b", trigger: .wallClock(floating, repeats: false)),
        ]
        // A pending request that falls outside the limit is removed rather than left to fire.
        let plan = NotificationPlanner.plan(
            wanted: wanted, pending: ["app.floating"], owns: { _ in true }, limit: 2, now: now, calendar: utc)
        #expect(plan.add.map(\.id) == ["app.weekly", "app.early"])
        #expect(plan.overLimit == 2)
        #expect(plan.remove == ["app.floating"])
    }

    @Test func equalTimesKeepTheAppsOrder() {
        let plan = NotificationPlanner.plan(
            wanted: [at("app.b", 60), at("app.a", 60)], pending: [], owns: { _ in true }, now: now, calendar: utc)
        #expect(plan.add.map(\.id) == ["app.b", "app.a"])
    }

    @Test func theSystemLimitIsSixtyFour() {
        let wanted = (1...80).map { at("app.\($0)", TimeInterval($0) * 60) }
        let plan = NotificationPlanner.plan(wanted: wanted, pending: [], owns: { _ in true }, now: now, calendar: utc)
        #expect(plan.add.count == 64)
        #expect(plan.add.last?.id == "app.64")
        #expect(plan.overLimit == 16)
    }
}
