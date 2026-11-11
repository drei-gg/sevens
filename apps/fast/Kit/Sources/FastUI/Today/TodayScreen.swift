import SwiftUI
import FastCore
import SevenKit

struct TodayScreen: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(FastModel.self) private var model
    @State private var showStart = false
    @State private var editFast: FastRecord?
    @State private var endFast: FastRecord?
    @State private var showJournal = false
    @State private var showWater = false
    @State private var remaining = false
    var body: some View {
        ScrollView {
            SevenAdaptiveColumns(accessibilityText: textSize.isAccessibilitySize) {
                TimelineView(.periodic(from: .now, by: 1)) { context in timer(at: context.date) }
                VStack(alignment: .leading, spacing: 20) {
                    if let active = model.archive.active {
                        GroupBox {
                            VStack(alignment: .leading, spacing: 16) {
                                LabeledContent(f("Started"), value: active.start.formatted(date: .abbreviated, time: .shortened))
                                if let goal = active.goalDate {
                                    LabeledContent(f("Planned finish"), value: goal.formatted(date: .abbreviated, time: .shortened))
                                }
                                Button(f("Edit start or goal")) { editFast = active }.accessibilityIdentifier("fast.editActive")
                                Button(f("End fast")) { endFast = active }.accessibilityIdentifier("fast.end")
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                        if !active.intention.isEmpty {
                            GroupBox(f("Your intention")) {
                                Text(active.intention).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    } else {
                        GroupBox {
                            VStack(alignment: .leading, spacing: 16) {
                                GatedButton(feature: f("Start a fast"), action: { showStart = true }) {
                                    Label(f("Start a fast"), systemImage: "play.fill").frame(maxWidth: .infinity)
                                }.buttonStyle(.borderedProminent).accessibilityIdentifier("fast.start")
                                NavigationLink { PlansScreen() } label: { Label(f("Plans & presets"), systemImage: "calendar.badge.clock") }
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                        if model.archive.settings.showEatingWindow, let last = model.archive.latestCompleted, let end = last.end {
                            GroupBox(f("Eating window")) {
                                TimelineView(.periodic(from: .now, by: 60)) { context in
                                    VStack(alignment: .leading, spacing: 8) {
                                        let finish = end.addingTimeInterval(Double(last.eatingMinutes) * 60)
                                        if context.date < finish {
                                            LabeledContent(f("Time remaining"), value: FastFormat.duration(finish.timeIntervalSince(context.date)))
                                            Text(f("A reminder, not a deadline. Start your next fast when it suits you.")).font(.footnote).foregroundStyle(.secondary)
                                        } else {
                                            Text(f("Your time is yours. There is no fast scheduled to start automatically.")).foregroundStyle(.secondary)
                                        }
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                        }
                    }
                    GroupBox(f("Notice your day")) {
                        VStack(alignment: .leading, spacing: 16) {
                            GatedButton(feature: f("Journal"), action: { showJournal = true }) { Label(f("Check in"), systemImage: "square.and.pencil") }
                            GatedButton(feature: f("Water"), action: { showWater = true }) {
                                LabeledContent { Text(waterToday) } label: { Label(f("Log water"), systemImage: "drop") }
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                    NavigationLink { HealthNoteScreen() } label: { Label(f("About fasting & this tracker"), systemImage: "info.circle") }
                        .font(.footnote)
                }
            }.padding().frame(maxWidth: 1200).frame(maxWidth: .infinity)
        }
        .navigationTitle(f("Today"))
        .toolbar { ToolbarItem(placement: .topBarTrailing) { NavigationLink { SettingsScreen() } label: { Image(systemName: "gearshape").accessibilityLabel(f("Settings")) } } }
        .sheet(isPresented: $showStart) { FastEditor(mode: .start) }
        .sheet(item: $editFast) { FastEditor(mode: .edit($0)) }
        .sheet(item: $endFast) { FastEditor(mode: .finish($0)) }
        .sheet(isPresented: $showJournal) { JournalEditor(fastID: model.archive.active?.id) }
        .sheet(isPresented: $showWater) { MetricEditor(kind: .water) }
    }
    @ViewBuilder private func timer(at now: Date) -> some View {
        VStack(spacing: 16) {
            if let fast = model.archive.active {
                let elapsed = fast.duration(at: now)
                let target = Double(fast.goalMinutes ?? 0) * 60
                let reached = target > 0 && elapsed >= target
                if textSize.isAccessibilitySize {
                    Text(f("Fasting")).font(.headline)
                    Text(FastFormat.duration(remaining && target > 0 ? max(0, target - elapsed) : elapsed)).font(.title).monospacedDigit()
                        .accessibilityIdentifier("fast.elapsed")
                    Text(f("Goal:") + " " + FastFormat.goal(fast.goalMinutes)).font(.subheadline).foregroundStyle(.secondary)
                } else {
                ZStack {
                    Circle().stroke(.quaternary, lineWidth: 10)
                    Circle().trim(from: 0, to: target > 0 ? min(1, elapsed / target) : 1)
                        .stroke(.tint, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    VStack(spacing: 10) {
                        Text(reached ? f("Goal reached") : remaining && target > 0 ? f("Remaining") : f("Fasting"))
                            .font(.headline).foregroundStyle(.secondary)
                        Text(FastFormat.duration(remaining && target > 0 ? max(0, target - elapsed) : elapsed))
                            .font(.system(.largeTitle, design: .rounded).weight(.medium)).monospacedDigit().minimumScaleFactor(0.6)
                            .accessibilityIdentifier("fast.elapsed")
                        Text(FastFormat.goal(fast.goalMinutes)).font(.subheadline).foregroundStyle(.secondary)
                    }.padding(22)
                }.frame(maxWidth: 290).aspectRatio(1, contentMode: .fit)
                .accessibilityElement(children: .combine)
                }
                if fast.goalMinutes != nil {
                    Button(remaining ? f("Show elapsed time") : f("Show time remaining")) { remaining.toggle() }.font(.subheadline)
                }
                if reached { Text(f("The timer keeps running until you end it.")).font(.footnote).foregroundStyle(.secondary) }
            } else {
                if textSize.isAccessibilitySize {
                    Text(f("No fast running")).font(.headline)
                } else {
                    Image(systemName: "sun.horizon").font(.system(size: 52, weight: .ultraLight)).foregroundStyle(.secondary).accessibilityHidden(true)
                    Text(f("At your own pace.")).font(.title2.weight(.medium))
                    Text(f("A little structure. Room for real life.")).foregroundStyle(.secondary)
                }
            }
        }.frame(maxWidth: .infinity).padding(.vertical, 18)
    }
    private var waterToday: String {
        let total = model.archive.metrics.filter { $0.kind == .water && Calendar.current.isDateInToday($0.date) }.reduce(0) { $0 + $1.value }
        return Units.display(total, kind: .water, settings: model.archive.settings).formatted(.number.precision(.fractionLength(0)))
            + " " + FastFormat.unit(.water, settings: model.archive.settings)
    }
}
