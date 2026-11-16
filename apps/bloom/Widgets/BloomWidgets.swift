import BloomSurfaces
import SwiftUI
import WidgetKit

struct BloomEntry: TimelineEntry {
  var date: Date
  var snapshot: SurfaceSnapshot?
}
struct BloomProvider: TimelineProvider {
  func placeholder(in context: Context) -> BloomEntry { BloomEntry(date: Date(), snapshot: nil) }
  func getSnapshot(in context: Context, completion: @escaping (BloomEntry) -> Void) {
    completion(entry())
  }
  func getTimeline(in context: Context, completion: @escaping (Timeline<BloomEntry>) -> Void) {
    completion(Timeline(entries: [entry()], policy: .after(Date().addingTimeInterval(1800))))
  }
  func entry() -> BloomEntry {
    BloomEntry(
      date: Date(),
      snapshot: SurfaceSnapshot.read(
        group: Bundle.main.object(forInfoDictionaryKey: "SevensAppGroup") as? String))
  }
}
struct BloomWidgetView: View {
  let entry: BloomEntry
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Label(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Cycle & Health", systemImage: "leaf").font(.headline)
      if let s = entry.snapshot, s.enabled, entry.date.timeIntervalSince(s.updated) < 86400 {
        if let day = s.cycleDay { Text("Cycle day \(day)").font(.title2) }
        if let next = s.nextReminder, next > entry.date {
          Text("Next reminder").font(.caption)
          Text(next, style: .time).font(.title2)
        } else {
          Text("Open your journal").font(.body)
        }
      } else {
        Text("Open the app for your private journal.").font(.body)
      }
    }.frame(maxWidth: .infinity, alignment: .leading).containerBackground(.background, for: .widget)
      .privacySensitive()
  }
}
@main struct BloomWidget: Widget {
  var body: some WidgetConfiguration {
    StaticConfiguration(kind: "bloom.snapshot", provider: BloomProvider()) {
      BloomWidgetView(entry: $0)
    }.configurationDisplayName("Your day").description("An optional, discreet reminder snapshot.")
      .supportedFamilies([.systemSmall, .systemMedium])
  }
}
