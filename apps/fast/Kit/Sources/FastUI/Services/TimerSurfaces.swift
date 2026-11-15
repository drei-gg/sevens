import Foundation
import WidgetKit
import ActivityKit
import FastCore
import FastSurfaces

@MainActor final class TimerSurfaces {
    private var last: TimerSnapshot?
    private var tail: Task<Void, Never>?
    func update(_ archive: FastArchive) async {
        let previous = tail
        let next = Task { if let previous { await previous.value }; await apply(archive) }
        tail = next
        await next.value
    }
    private func apply(_ archive: FastArchive) async {
        let snapshot = TimerSnapshot(archive: archive)
        if last != snapshot,
           let group = Bundle.main.object(forInfoDictionaryKey: "SevensAppGroup") as? String,
           let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) {
            do {
                let data = try JSONEncoder().encode(snapshot)
                try data.write(to: directory.appendingPathComponent(TimerSnapshot.filename), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                WidgetCenter.shared.reloadTimelines(ofKind: "FastTimer")
                last = snapshot
            } catch { /* Tracking is already saved; retry the widget snapshot next foreground. */ }
        }
        await Self.updateActivity(snapshot, enabled: archive.settings.liveActivity)
    }
    nonisolated private static func updateActivity(_ snapshot: TimerSnapshot, enabled: Bool) async {
        let activities = Activity<FastActivityAttributes>.activities
        for activity in activities where activity.attributes.fastID != snapshot.fastID || !enabled {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        guard enabled, let id = snapshot.fastID, let start = snapshot.start else { return }
        let content = ActivityContent(state: FastActivityAttributes.ContentState(start: start, goal: snapshot.goal), staleDate: nil)
        if let activity = activities.first(where: { $0.attributes.fastID == id && ($0.activityState == .active || $0.activityState == .stale) }) {
            await activity.update(content)
        } else if ActivityAuthorizationInfo().areActivitiesEnabled {
            // System-controlled duration: Live Activities are not the source of timer truth.
            _ = try? Activity.request(attributes: FastActivityAttributes(fastID: id), content: content, pushType: nil)
        }
    }
}
