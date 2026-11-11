import Foundation
import Testing
@testable import FastCore

@Test func remindersFollowActualEndAndCancelDuringNextFast() throws {
    let now = Date(timeIntervalSince1970: 1_790_000_000)
    var archive = FastArchive(); archive.settings.eatingReminder = true; archive.settings.goalReminder = true
    let fast = FastRecord(start: now.addingTimeInterval(-16 * 3_600), end: now, eatingMinutes: 480)
    archive.fasts = [fast]
    let events = ReminderPlan.events(archive: archive, now: now)
    #expect(events.count == 1)
    #expect(events[0].date == now.addingTimeInterval(8 * 3_600))
    archive.fasts.append(FastRecord(start: now, goalMinutes: 60))
    let changed = ReminderPlan.events(archive: archive, now: now)
    #expect(changed.count == 1); #expect(changed[0].kind == .goal)
    #expect(ReminderPlan.events(archive: archive, now: now.addingTimeInterval(3_601)).isEmpty)
}

@Test func scheduleNeverAutoStartsAndStaysBelowNotificationLimit() {
    let now = Date(timeIntervalSince1970: 1_790_000_000)
    var archive = FastArchive(); archive.settings.scheduleReminders = true
    archive.settings.reminderWeekdays = [1,2,3,4,5,6,7]
    archive.plans = (1...100).map { PlannedFast(start: now.addingTimeInterval(Double($0) * 86_400)) }
    #expect(ReminderPlan.events(archive: archive, now: now).count == 47)
    #expect(archive.active == nil)
    #expect(ReminderPlan.events(archive: archive, now: now).filter { $0.kind == .weekly }.allSatisfy { $0.hour == 20 })
}
