import Foundation

public struct ReminderEvent: Equatable, Sendable, Identifiable {
    public enum Kind: String, Sendable { case goal, eating, planned, weekly, water }
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
        for day in settings.reminderWeekdays.sorted() {
            result.append(ReminderEvent(id: "fast.weekly.\(day)", kind: .weekly, weekday: day,
                                        hour: settings.reminderHour, minute: settings.reminderMinute))
        }
        for hour in settings.water.reminderHours {
            result.append(ReminderEvent(id: "fast.water.\(hour)", kind: .water, hour: hour, minute: 0))
        }
        // Reserve room for repeating reminders and the current timer. Nearest plans win
        // the remaining slots; later plans are reconsidered whenever the app reconciles.
        if settings.scheduleReminders {
            for plan in archive.plans.filter({ $0.start > now }).sorted(by: { $0.start < $1.start }).prefix(min(40, max(0, 64 - result.count))) {
                result.append(ReminderEvent(id: "fast.plan.\(plan.id)", kind: .planned, date: plan.start))
            }
        }
        return result
    }
}
