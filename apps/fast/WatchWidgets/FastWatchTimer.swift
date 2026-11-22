import SwiftUI
import WidgetKit
import FastWire

struct WatchTimerEntry: TimelineEntry {
    let date: Date
    let snapshot: WatchTimerSnapshot?
}

struct WatchTimerProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchTimerEntry {
        let now = Date()
        return WatchTimerEntry(date: now, snapshot: WatchTimerSnapshot(FastWatchSnapshot(
            revision: UUID(), generatedAt: now, activeID: UUID(),
            start: now.addingTimeInterval(-3_600), goal: now.addingTimeInterval(3_600),
            waterMilliliters: 0, canCreateRecords: false)))
    }
    func getSnapshot(in context: Context, completion: @escaping (WatchTimerEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : read())
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchTimerEntry>) -> Void) {
        let entry = read()
        var entries = [entry]
        if let goal = entry.snapshot?.goal, goal > entry.date {
            entries.append(WatchTimerEntry(date: goal, snapshot: entry.snapshot))
        }
        // Dynamic timer views count without polling. A received phone snapshot reloads this kind.
        completion(Timeline(entries: entries, policy: .never))
    }
    private func read() -> WatchTimerEntry {
        var snapshot: WatchTimerSnapshot?
        if let group = Bundle.main.object(forInfoDictionaryKey: "SevensAppGroup") as? String,
           let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
           let input = try? FileHandle(forReadingFrom: directory.appendingPathComponent(WatchTimerSnapshot.filename)) {
            defer { try? input.close() }
            if let data = try? input.read(upToCount: FastWatchCodec.maximumBytes + 1) {
                snapshot = try? WatchTimerSnapshot.decode(data)
            }
        }
        return WatchTimerEntry(date: Date(), snapshot: snapshot)
    }
}

struct WatchTimerView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WatchTimerEntry
    var body: some View {
        Group {
            if let snapshot = entry.snapshot {
                if family == .accessoryRectangular {
                    VStack(alignment: .leading, spacing: 2) {
                        Label(snapshot.start == nil ? "No fast running" : "Fasting", systemImage: "timer")
                            .font(.caption)
                        if let start = snapshot.start {
                            Text(start, style: .timer).font(.headline).monospacedDigit()
                        }
                        Text("Synced \(snapshot.generatedAt, format: .dateTime.month(.abbreviated).day().hour().minute())")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                } else if family == .accessoryCircular,
                          let start = snapshot.start, let goal = snapshot.goal, goal > start {
                    ProgressView(timerInterval: start...goal, countsDown: false) { Image(systemName: "timer") }
                        .progressViewStyle(.circular)
                } else if let start = snapshot.start {
                    Text(start, style: .timer).monospacedDigit()
                } else {
                    Text("No fast")
                }
            } else {
                Label("Sync Fast", systemImage: "iphone")
            }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }
}

@main struct FastWatchTimer: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WatchTimerSnapshot.widgetKind, provider: WatchTimerProvider()) {
            WatchTimerView(entry: $0)
        }
        .configurationDisplayName("Fasting timer")
        .description("Your last synced timer. Open Fast to refresh or make changes.")
        .supportedFamilies([.accessoryInline, .accessoryCircular, .accessoryRectangular])
    }
}
