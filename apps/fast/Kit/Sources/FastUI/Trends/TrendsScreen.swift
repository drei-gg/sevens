import SwiftUI
import Charts
import FastCore
import SevenKit

struct TrendsScreen: View {
    @Environment(FastModel.self) private var model
    @State private var range = 30
    @State private var metric: MetricKind?
    @State private var periodEnd = Date()
    private var interval: DateInterval {
        let end = Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: periodEnd))!
        return DateInterval(start: Calendar.current.date(byAdding: .day, value: -range, to: end)!, end: end)
    }
    var body: some View {
        let summary = HistoryMath.summary(in: interval, archive: model.archive, now: Date())
        let days = HistoryMath.days(in: interval, archive: model.archive, calendar: .current, now: Date())
        List {
            Section {
                Picker(f("Period"), selection: $range) {
                    Text(f("Week")).tag(7); Text(f("Month")).tag(30); Text(f("Quarter")).tag(90); Text(f("Year")).tag(365)
                }.pickerStyle(.segmented)
                DatePicker(f("Ending"), selection: $periodEnd, in: ...Date(), displayedComponents: .date)
                LabeledContent(f("Recorded hours"), value: (summary.seconds / 3_600).formatted(.number.precision(.fractionLength(1))))
                LabeledContent(f("Completed fasts"), value: summary.completedCount.formatted())
                LabeledContent(f("Average duration"), value: summary.averageDuration.map(FastFormat.duration) ?? "—")
                if summary.goalsCount > 0 {
                    LabeledContent(f("Goals reached"), value: "\(summary.goalsMet) / \(summary.goalsCount)")
                }
            } footer: { Text(f("Hours include portions of fasts within this period. Completed fasts and averages use sessions that ended in it. Open-ended fasts have no goal score.")) }
            Section(f("Your rhythm")) {
                if days.contains(where: { $0.seconds > 0 }) {
                    Chart(days) { day in
                        BarMark(x: .value(f("Date"), day.date, unit: .day), y: .value(f("Hours"), day.seconds / 3_600))
                            .accessibilityLabel(day.date.formatted(date: .abbreviated, time: .omitted))
                            .accessibilityValue(FastFormat.duration(day.seconds))
                    }.frame(height: 180).chartYAxisLabel(f("Hours"))
                } else { Text(f("Your first records will appear here.")).foregroundStyle(.secondary) }
            }
            if model.archive.settings.showWeight {
                metricSection(.weight)
            }
            metricSection(.water)
            metricSection(.waist)
            metricSection(.sleep)
            Section(f("Your check-ins")) {
                let entries = model.archive.journal.filter { $0.date >= interval.start && $0.date < interval.end }
                LabeledContent(f("Observations"), value: entries.count.formatted())
                ForEach([f("Mood"), f("Energy"), f("Hunger")], id: \.self) { label in
                    let values = entries.compactMap { entry in label == f("Mood") ? entry.mood : label == f("Energy") ? entry.energy : entry.hunger }
                    if !values.isEmpty {
                        LabeledContent(label) {
                            Text((Double(values.reduce(0,+))/Double(values.count)).formatted(.number.precision(.fractionLength(1))) + " / 5 · n=\(values.count)")
                        }
                    }
                }
                Text(f("These describe your entries. They do not show that fasting caused a change.")).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(f("Trends"))
        .sheet(item: Binding(get: { metric.map(MetricSelection.init) }, set: { metric = $0?.kind })) { MetricEditor(kind: $0.kind) }
    }
    @ViewBuilder private func metricSection(_ kind: MetricKind) -> some View {
        let entries = model.archive.metrics.filter { $0.kind == kind && $0.date >= interval.start && $0.date < interval.end }.sorted { $0.date < $1.date }
        Section(FastFormat.kind(kind)) {
            if !entries.isEmpty {
                if kind == .water {
                    let values = Dictionary(grouping: entries, by: { Calendar.current.startOfDay(for: $0.date) })
                    Chart(values.keys.sorted(), id: \.self) { day in
                        BarMark(x: .value(f("Date"), day, unit: .day), y: .value(FastFormat.unit(kind, settings: model.archive.settings),
                            Units.display(values[day]!.reduce(0) { $0 + $1.value }, kind: kind, settings: model.archive.settings)))
                    }.frame(height: 140)
                } else {
                    Chart(entries) { entry in
                        LineMark(x: .value(f("Date"), entry.date), y: .value(FastFormat.unit(kind, settings: model.archive.settings), Units.display(entry.value, kind: kind, settings: model.archive.settings)))
                        PointMark(x: .value(f("Date"), entry.date), y: .value(FastFormat.unit(kind, settings: model.archive.settings), Units.display(entry.value, kind: kind, settings: model.archive.settings)))
                    }.frame(height: 140).chartYScale(domain: .automatic(includesZero: false))
                    if let first = entries.first, let last = entries.last {
                        LabeledContent(f("First → latest in this period"), value: metricValue(first) + " → " + metricValue(last))
                    }
                }
                NavigationLink(f("View & edit measurements")) { MetricHistoryScreen(kind: kind) }
            } else { Text(f("Nothing logged in this period.")).foregroundStyle(.secondary) }
            GatedButton(feature: FastFormat.kind(kind), action: { metric = kind }) { Label(f("Add measurement"), systemImage: "plus") }
        }
    }
    private func metricValue(_ entry: MetricEntry) -> String {
        Units.display(entry.value, kind: entry.kind, settings: model.archive.settings).formatted(.number.precision(.fractionLength(0...1)))
            + " " + FastFormat.unit(entry.kind, settings: model.archive.settings)
    }
}
private struct MetricSelection: Identifiable { let kind: MetricKind; var id: String { kind.rawValue }; init(_ kind: MetricKind) { self.kind = kind } }
