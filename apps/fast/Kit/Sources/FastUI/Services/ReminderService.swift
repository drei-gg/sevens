import Foundation
import UserNotifications
import FastCore

actor ReminderService {
    private var tail: Task<String, Never>?
    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }
    /// Serialize reconciliations across suspension points; an older snapshot cannot win a race.
    func update(_ archive: FastArchive) async -> String {
        let previous = tail
        let task = Task { if let previous { _ = await previous.value }; return await reconcile(archive) }
        tail = task
        return await task.value
    }
    private func reconcile(_ archive: FastArchive) async -> String {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        center.removePendingNotificationRequests(withIdentifiers: pending.filter { $0.identifier.hasPrefix("fast.") }.map(\.identifier))
        let events = ReminderPlan.events(archive: archive, now: Date())
        guard !events.isEmpty else { return f("Reminders are off.") }
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
            return f("Notifications are not allowed. Tracking still works; enable notifications in iPhone Settings for reminders.")
        }
        do {
            for event in events {
                let content = UNMutableNotificationContent()
                content.title = f("Fast Tracker"); content.sound = .default
                switch event.kind {
                case .goal: content.body = f("Your planned fasting time has elapsed. End your fast when you are ready.")
                case .eating: content.body = f("Your planned eating window has elapsed. Adjust your day as you need.")
                case .planned, .weekly: content.body = f("A time you set aside for fasting. Start only if it suits your day.")
                }
                let trigger: UNNotificationTrigger
                if let date = event.date {
                    guard date > Date() else { continue }
                    trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, date.timeIntervalSinceNow), repeats: false)
                } else {
                    var parts = DateComponents(); parts.weekday = event.weekday; parts.hour = event.hour; parts.minute = event.minute
                    trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: true)
                }
                try await center.add(UNNotificationRequest(identifier: event.id, content: content, trigger: trigger))
            }
            return f("Reminders are enabled. Delivery follows your iPhone notification and Focus settings.")
        } catch { return f("Some reminders could not be scheduled. Reopen reminder settings and try again.") }
    }
}
