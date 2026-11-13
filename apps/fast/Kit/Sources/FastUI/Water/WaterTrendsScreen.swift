import SwiftUI
import SevenKit
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
        let settings = model.archive.settings
        List {
            Section {
                VStack(spacing: Tokens.Space.ms) {
                    Picker(f("Period"), selection: $count) {
                        Text(f("Week")).tag(7); Text(f("Month")).tag(30)
                        Text(f("Quarter")).tag(90); Text(f("Year")).tag(365)
                    }.pickerStyle(.segmented)
                    HStack {
                        Text(f("Ending")).textRole(.secondary)
                        Spacer(minLength: 0)
                        DatePicker(f("Ending"), selection: $end, in: ...Date(), displayedComponents: .date).labelsHidden()
                    }
                    StatGrid {
                        StatTile(title: f("Total logged"), value: waterAmount(total, settings: settings), tint: Tokens.dataCool)
                        StatTile(title: f("Days with entries"), value: "\(logged.count) / \(count)")
                        if !logged.isEmpty {
                            StatTile(title: f("Average on logged days"), value: waterAmount(total / Double(logged.count), settings: settings))
                        }
                    }
                    if !logged.isEmpty {
                        Chart {
                            ForEach(days) { day in
                                BarMark(x: .value(f("Date"), day.date, unit: .day),
                                        y: .value(FastFormat.unit(.water, settings: settings), Units.display(day.milliliters, kind: .water, settings: settings)))
                                    .cornerRadius(count > 90 ? 1 : 4)
                                    .foregroundStyle(Tokens.dataCool)
                                    .accessibilityLabel(day.date.formatted(date: .abbreviated, time: .omitted))
                                    .accessibilityValue(day.entryCount == 0 ? f("No entries") : waterAmount(day.milliliters, settings: settings))
                            }
                            if let goal = settings.water.goalMilliliters {
                                RuleMark(y: .value(f("Your goal"), Units.display(goal, kind: .water, settings: settings)))
                                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4])).foregroundStyle(Tokens.muted)
                            }
                        }
                        .chartYAxisLabel(FastFormat.unit(.water, settings: settings))
                        .frame(height: 200)
                        .padding(Tokens.Space.ms)
                        .background(Tokens.card, in: RoundedRectangle(cornerRadius: Tokens.Radius.card))
                    }
                    Text(f("These are recorded amounts. A day without entries does not mean you drank no water."))
                        .textRole(.caption).frame(maxWidth: .infinity, alignment: .leading)
                }
                .bareListRow()
            }
            Section(f("Daily water")) {
                if logged.isEmpty { Text(f("Nothing logged in this period.")).foregroundStyle(Tokens.muted) }
                ForEach(days.reversed()) { day in
                    LabeledContent(day.date.formatted(date: .abbreviated, time: .omitted),
                                   value: day.entryCount == 0 ? f("No entries") : waterAmount(day.milliliters, settings: model.archive.settings))
                }
            }
        }.sevenScreenBackground().navigationTitle(f("Water trends"))
    }
}
