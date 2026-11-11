import Foundation

public struct ReminderEvent: Equatable, Sendable, Identifiable {
    public enum Kind: String, Sendable { case goal, eating, planned, weekly }
    public var id: String
    public var kind: Kind
    public var date: Date?
    public var weekday: Int?
    public var hour: Int?
    public var minute: Int?
}

public enum ReminderPlan {
    public static func events(archive: FastArchive, now: Date) -> [ReminderEvent] {
        let settings = archive.settings
        var result: [ReminderEvent] = []
        if settings.goalReminder, let active = archive.active, let goal = active.goalDate, goal > now {
            result.append(ReminderEvent(id: "fast.goal.\(active.id)", kind: .goal, date: goal))
        }
        if settings.eatingReminder, archive.active == nil, let last = archive.latestCompleted, let end = last.end {
            let finish = end.addingTimeInterval(Double(last.eatingMinutes) * 60)
            if finish > now { result.append(ReminderEvent(id: "fast.eating.\(last.id)", kind: .eating, date: finish)) }
        }
        if settings.scheduleReminders {
            for plan in archive.plans.filter({ $0.start > now }).sorted(by: { $0.start < $1.start }).prefix(40) {
                result.append(ReminderEvent(id: "fast.plan.\(plan.id)", kind: .planned, date: plan.start))
            }
        }
        for day in settings.reminderWeekdays.sorted() {
            result.append(ReminderEvent(id: "fast.weekly.\(day)", kind: .weekly, weekday: day,
                                        hour: settings.reminderHour, minute: settings.reminderMinute))
        }
        return result
    }
}
