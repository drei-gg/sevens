import Foundation

/// When a local notification fires. `at` is an instant (a goal time, an appointment, a snooze);
/// `wallClock` is a time on the clock in whatever zone the phone is in when it fires, so it follows
/// travel and daylight saving without the app running (a weekly reminder, a pill at 08:00).
public enum NotificationTrigger: Sendable, Equatable {
    case at(Date)
    case wallClock(DateComponents, repeats: Bool)
}

/// One local notification an app wants pending. The identifier is the app's stable one: the same
/// identifier replaces the pending request in place.
public struct ScheduledNotification: Sendable, Equatable {
    public var id: String
    public var title: String
    public var body: String
    public var sound: Bool
    public var category: String?
    public var userInfo: [String: String]
    public var trigger: NotificationTrigger

    public init(
        id: String, title: String, body: String, sound: Bool = true, category: String? = nil,
        userInfo: [String: String] = [:], trigger: NotificationTrigger
    ) {
        self.id = id; self.title = title; self.body = body; self.sound = sound
        self.category = category; self.userInfo = userInfo; self.trigger = trigger
    }
}

/// What a reconcile does to the pending queue: remove what is no longer wanted, then add (or
/// replace) what is, within the system's limit.
public struct NotificationPlan: Sendable, Equatable {
    /// Pending identifiers this app owns that are no longer wanted.
    public var remove: [String]
    /// Requests to add or replace, soonest first, at most the limit.
    public var add: [ScheduledNotification]
    /// One-off requests whose moment has already passed: nothing to schedule.
    public var past: Int
    /// Wanted requests left out because the queue is full (the soonest are kept).
    public var overLimit: Int
}

/// How the Sevens keep iOS's pending-notification queue in step with a schedule (SevenSchedule's
/// NotificationScheduler applies it). iOS keeps at most 64 pending requests per app and silently
/// drops the rest, so the plan keeps the soonest and says how many it left out.
public enum NotificationPlanner {
    /// iOS's per-app pending-request limit.
    public static let systemLimit = 64

    public static func plan(
        wanted: [ScheduledNotification], pending: [String], owns: (String) -> Bool,
        limit: Int = systemLimit, now: Date, calendar: Calendar = .current
    ) -> NotificationPlan {
        var past = 0
        var upcoming: [ScheduledNotification] = []
        for request in wanted {
            if case .at(let date) = request.trigger, date <= now { past += 1; continue }
            upcoming.append(request)
        }
        // Repeating requests first (they recur, and each app's core leaves room for them), then
        // one-offs by when they fire; ties keep the app's order.
        let ordered = upcoming.enumerated().sorted { a, b in
            let (ka, kb) = (key(a.element, calendar), key(b.element, calendar))
            return ka == kb ? a.offset < b.offset : ka < kb
        }.map(\.element)
        let kept = Array(ordered.prefix(max(0, limit)))
        let wantedIDs = Set(kept.map(\.id))
        return NotificationPlan(
            remove: pending.filter { owns($0) && !wantedIDs.contains($0) },
            add: kept, past: past, overLimit: ordered.count - kept.count)
    }

    private static func key(_ request: ScheduledNotification, _ calendar: Calendar) -> Date {
        switch request.trigger {
        case .at(let date): return date
        case .wallClock(_, repeats: true): return .distantPast
        case .wallClock(let parts, repeats: false): return calendar.date(from: parts) ?? .distantFuture
        }
    }
}
