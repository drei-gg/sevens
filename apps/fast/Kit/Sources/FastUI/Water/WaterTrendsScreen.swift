import SwiftUI
import Charts
import FastCore

struct WaterTrendsScreen: View {
    @Environment(FastModel.self) private var model
    @State private var count = 30
    @State private var end = Date()
    var body: some View {
        let days = WaterHistory.days(model.archive.metrics, ending: end, count: count)
        let logged = days.filter { $0.entryCount > 0 }
        let total = days.reduce(0) { $0 + $1.milliliters }
        List {
            Section {
                Picker(f("Period"), selection: $count) {
                    Text(f("Week")).tag(7); Text(f("Month")).tag(30)
                    Text(f("Quarter")).tag(90); Text(f("Year")).tag(365)
                }.pickerStyle(.segmented)
                DatePicker(f("Ending"), selection: $end, in: ...Date(), displayedComponents: .date)
                LabeledContent(f("Total logged"), value: waterAmount(total, settings: model.archive.settings))
                LabeledContent(f("Days with entries"), value: "\(logged.count) / \(count)")
                if !logged.isEmpty {
                    LabeledContent(f("Average on logged days"), value: waterAmount(total / Double(logged.count), settings: model.archive.settings))
                }
            } footer: { Text(f("These are recorded amounts. A day without entries does not mean you drank no water.")) }
            Section(f("Daily water")) {
                if logged.isEmpty { Text(f("Nothing logged in this period.")).foregroundStyle(.secondary) }
                else {
                    Chart(days) { day in
                        BarMark(x: .value(f("Date"), day.date, unit: .day),
                                y: .value(FastFormat.unit(.water, settings: model.archive.settings), Units.display(day.milliliters, kind: .water, settings: model.archive.settings)))
                            .accessibilityLabel(day.date.formatted(date: .abbreviated, time: .omitted))
                            .accessibilityValue(day.entryCount == 0 ? f("No entries") : waterAmount(day.milliliters, settings: model.archive.settings))
                    }.frame(height: 180).chartYAxisLabel(FastFormat.unit(.water, settings: model.archive.settings))
                }
                ForEach(days.reversed()) { day in
                    LabeledContent(day.date.formatted(date: .abbreviated, time: .omitted),
                                   value: day.entryCount == 0 ? f("No entries") : waterAmount(day.milliliters, settings: model.archive.settings))
                }
            }
        }.navigationTitle(f("Water trends"))
    }
}
