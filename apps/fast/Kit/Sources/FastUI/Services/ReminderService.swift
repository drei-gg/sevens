import Foundation
import FastCore
import SevenKitCore
import SevenSchedule

/// Fast's local notifications on SevenKit's NotificationScheduler, which owns every `fast.`
/// identifier, runs one reconcile at a time and keeps the queue within iOS's limit. Which reminders
/// exist comes from the core (ReminderPlan); this supplies the words and the triggers.
actor ReminderService {
    private let scheduler = NotificationScheduler(prefix: "fast.")

    func requestPermission() async -> Bool {
        (try? await scheduler.requestAuthorization([.alert, .sound])) ?? false
    }

    func update(_ archive: FastArchive) async -> String {
        let events = ReminderPlan.events(archive: archive, now: Date())
        let outcome = await scheduler.reconcile(events.map(Self.request))
        if events.isEmpty { return f("Reminders are off.") }
        if !outcome.allowed {
            return f("Notifications are not allowed. Tracking still works; enable notifications in iPhone Settings for reminders.")
        }
        if outcome.failed > 0 { return f("Some reminders could not be scheduled. Reopen reminder settings and try again.") }
        return f("Reminders are enabled. Delivery follows your iPhone notification and Focus settings.")
    }

    private static func request(_ event: ReminderEvent) -> ScheduledNotification {
        let body: String
        switch event.kind {
        case .water: body = f("A water break, if it suits your day. Log a drink in Fast.")
        case .goal: body = f("Your planned fasting time has elapsed. End your fast when you are ready.")
        case .eating: body = f("Your planned eating window has elapsed. Adjust your day as you need.")
        case .planned, .weekly: body = f("A time you set aside for fasting. Start only if it suits your day.")
        }
        let trigger: NotificationTrigger
        if let date = event.date {
            trigger = .at(date)
        } else {
            var parts = DateComponents()
            parts.weekday = event.weekday.map(Int.init); parts.hour = event.hour.map(Int.init); parts.minute = event.minute.map(Int.init)
            trigger = .wallClock(parts, repeats: true)
        }
        return ScheduledNotification(id: event.id, title: f("Fast Tracker"), body: body, trigger: trigger)
    }
}
