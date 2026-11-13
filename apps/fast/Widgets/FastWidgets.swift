import SwiftUI
import WidgetKit
import ActivityKit
import FastWire
import FastSurfaces

struct TimerEntry: TimelineEntry { let date: Date; let snapshot: TimerSnapshot }
struct TimerProvider: TimelineProvider {
    func placeholder(in context: Context) -> TimerEntry {
        TimerEntry(date: Date(), snapshot: TimerSnapshot(start: Date().addingTimeInterval(-3_600), goal: Date().addingTimeInterval(3_600)))
    }
    func getSnapshot(in context: Context, completion: @escaping (TimerEntry) -> Void) { completion(read()) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<TimerEntry>) -> Void) {
        let entry = read()
        var entries = [entry]
        if let goal = entry.snapshot.goal, goal > entry.date { entries.append(TimerEntry(date: goal, snapshot: entry.snapshot)) }
        completion(Timeline(entries: entries, policy: .after(Date().addingTimeInterval(30 * 60))))
    }
    private func read() -> TimerEntry {
        var snapshot = TimerSnapshot()
        if let group = Bundle.main.object(forInfoDictionaryKey: "SevensAppGroup") as? String,
           let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
           let data = try? Data(contentsOf: directory.appendingPathComponent(TimerSnapshot.filename)),
           let decoded = try? JSONDecoder().decode(TimerSnapshot.self, from: data) { snapshot = decoded }
        return TimerEntry(date: Date(), snapshot: snapshot)
    }
}
struct TimerWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TimerEntry
    var body: some View {
        Group {
            if family == .accessoryInline {
                if let start = entry.snapshot.start { Text(start, style: .timer) }
                else { Text("No fast running") }
            } else if family == .accessoryCircular {
                if let start = entry.snapshot.start, let goal = entry.snapshot.goal, goal > start {
                    ProgressView(timerInterval: start...goal, countsDown: false) { Image(systemName: "timer") }
                        .progressViewStyle(.circular)
                } else { Image(systemName: "timer") }
            } else if family == .accessoryRectangular {
                VStack(alignment: .leading) {
                    Label("Fasting", systemImage: "timer").font(.caption)
                    if let start = entry.snapshot.start { Text(start, style: .timer).font(.headline).monospacedDigit() }
                    else { Text("No fast running").font(.caption) }
                }
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Label("Fast Tracker", systemImage: "timer").font(.caption).foregroundStyle(.secondary)
                    if let start = entry.snapshot.start {
                        Text(start, style: .timer).font(.system(.title, design: .rounded)).monospacedDigit().minimumScaleFactor(0.5)
                        if let goal = entry.snapshot.goal {
                            Text(goal <= entry.date ? "Goal reached" : "Planned finish").font(.caption).foregroundStyle(.secondary)
                            if goal > entry.date { Text(goal, style: .time).font(.caption) }
                        } else { Text("Open ended").font(.caption).foregroundStyle(.secondary) }
                    } else { Text("At your own pace.").font(.headline); Text("Open to start a fast").font(.caption).foregroundStyle(.secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }.containerBackground(.fill.tertiary, for: .widget)
    }
}
struct FastTimerWidget: Widget {
    let kind = "FastTimer"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: TimerProvider()) { TimerWidgetView(entry: $0) }
            .configurationDisplayName("Fasting timer")
            .description("Your current timer, without journal text or measurements.")
            .supportedFamilies([.systemSmall, .systemMedium, .accessoryInline, .accessoryCircular, .accessoryRectangular])
    }
}
struct FastLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FastActivityAttributes.self) { context in
            HStack {
                Image(systemName: "timer").font(.title2)
                VStack(alignment: .leading) {
                    Text("Fasting").font(.headline)
                    Text(context.state.start, style: .timer).monospacedDigit()
                }
                Spacer()
                if let goal = context.state.goal {
                    VStack(alignment: .trailing) { Text("Planned finish").font(.caption); Text(goal, style: .time) }
                }
            }.padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Label("Fasting", systemImage: "timer") }
                DynamicIslandExpandedRegion(.trailing) { Text(context.state.start, style: .timer).monospacedDigit() }
                DynamicIslandExpandedRegion(.bottom) {
                    if let goal = context.state.goal { HStack { Text("Planned finish"); Spacer(); Text(goal, style: .time) } }
                    else { Text("Open ended") }
                }
            } compactLeading: { Image(systemName: "timer") }
              compactTrailing: { Text(context.state.start, style: .timer).monospacedDigit().frame(maxWidth: 65) }
              minimal: { Image(systemName: "timer") }
        }
    }
}
@main struct FastWidgets: WidgetBundle {
    var body: some Widget { FastTimerWidget(); FastLiveActivity() }
}
