import AlarmCore
import SwiftUI
import WidgetKit

struct AlarmEntry: TimelineEntry {
    var date: Date
    var snapshot: AlarmWidgetSnapshot?
}
struct AlarmProvider: TimelineProvider {
    func placeholder(in context: Context) -> AlarmEntry { AlarmEntry(date: Date(), snapshot: nil) }
    func getSnapshot(in context: Context, completion: @escaping (AlarmEntry) -> Void) { completion(entry()) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<AlarmEntry>) -> Void) {
        let initial = entry()
        var entries = [initial]
        if let snapshot = initial.snapshot {
            // Predictable transitions are supplied to WidgetKit, not a background polling loop.
            let dates = Set(snapshot.items.map(\.date) + [snapshot.validUntil]).sorted()
            for date in dates where date > initial.date && date <= snapshot.validUntil {
                entries.append(AlarmEntry(date: date, snapshot: snapshot))
            }
        }
        completion(Timeline(entries: entries, policy: .after(initial.date.addingTimeInterval(1800))))
    }
    private func entry() -> AlarmEntry {
        AlarmEntry(date: Date(), snapshot: AlarmWidgetStore.read(from: AlarmWidgetStore.url(
            group: Bundle.main.object(forInfoDictionaryKey: "SevensAppGroup") as? String)))
    }
}
struct AlarmWidgetView: View {
    let entry: AlarmEntry
    @Environment(\.widgetFamily) private var family
    var body: some View {
        let view = entry.snapshot.map { alarmWidgetView(snapshot: $0, now: entry.date, timeZone: TimeZone.current.identifier) }
        VStack(alignment: .leading, spacing: 6) {
            Text("Saved schedule").font(.caption).foregroundStyle(.secondary)
            if let view, !view.stale {
                if view.items.isEmpty { Text("No upcoming times").font(.headline) }
                ForEach(Array(view.items.prefix(family == .systemMedium ? 2 : 1).enumerated()), id: \.offset) { _, item in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(item.date, style: .time).font(.title2).monospacedDigit()
                            if family == .systemMedium { Text(item.name).font(.subheadline).lineLimit(1) }
                        }
                        if family != .systemMedium { Text(item.name).font(.subheadline).lineLimit(1) }
                        HStack {
                            Text(item.date, format: .dateTime.month(.abbreviated).day()).font(.caption)
                            Text(item.purpose == "snooze" ? "Snooze" : item.purpose == "followUp" ? "Wake-up check" : item.delivery == .alarm ? "Alarm" : "Quiet reminder").font(.caption)
                        }.foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
                Group {
                    if view.needsAttention { Text("Open to check scheduling") }
                    else if let snapshot = entry.snapshot { Text("Checked \(snapshot.updated, format: .dateTime.hour().minute())") }
                }.font(.caption2).foregroundStyle(.secondary).lineLimit(2)
            } else {
                Text("Open to refresh").font(.headline)
                Text("Check your saved schedule in the app.").font(.caption).foregroundStyle(.secondary)
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
            .containerBackground(.background, for: .widget).privacySensitive()
    }
}
@main struct AlarmScheduleWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "alarm.schedule", provider: AlarmProvider()) { AlarmWidgetView(entry: $0) }
            .configurationDisplayName("Saved schedule")
            .description("Upcoming alarms and loops, including routine names. Open the app to check scheduling. This does not confirm delivery.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
