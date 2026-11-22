import SwiftUI
import FastCore
import SevenKit

struct TodayScreen: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(FastModel.self) private var model
    @State private var showStart = false
    @State private var editFast: FastRecord?
    @State private var endFast: FastRecord?
    @State private var showJournal = false
    @State private var remaining = false
    var body: some View {
        ScrollView {
            SevenAdaptiveColumns(accessibilityText: textSize.isAccessibilitySize) {
                VStack(spacing: Tokens.Space.l) {
                    TimelineView(.periodic(from: .now, by: 1)) { context in hero(at: context.date) }
                    actions
                }
                VStack(alignment: .leading, spacing: Tokens.Space.m) {
                    if let active = model.archive.active, !active.intention.isEmpty {
                        GroupBox(f("Your intention")) {
                            Text(active.intention).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    WaterCard()
                    GroupBox {
                        GatedButton(feature: f("Journal"), action: { showJournal = true }) {
                            HStack(spacing: Tokens.Space.ms) {
                                Image(systemName: "square.and.pencil").textRole(.section).foregroundStyle(Tokens.muted)
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                                    Text(f("Check in")).textRole(.rowTitle, emphasized: true)
                                    Text(f("Notice your day")).textRole(.secondary)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right").textRole(.caption).accessibilityHidden(true)
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain)
                        .accessibilityLabel(f("Check in")).accessibilityHint(f("Notice your day"))
                        .accessibilityIdentifier("journal.checkIn")
                    }
                    NavigationLink { HealthNoteScreen() } label: { Label(f("About fasting & this tracker"), systemImage: "info.circle") }
                        .foregroundStyle(.tint).textRole(.caption)
                }
            }.padding().frame(maxWidth: 1200).frame(maxWidth: .infinity)
        }
        .sevenScreenBackground().navigationTitle(f("Today"))
        .toolbar { ToolbarItem(placement: .topBarTrailing) { NavigationLink { SettingsScreen() } label: { Image(systemName: "gearshape").accessibilityLabel(f("Settings")) } } }
        .sheet(isPresented: $showStart) { FastEditor(mode: .start) }
        .sheet(item: $editFast) { FastEditor(mode: .edit($0)) }
        .sheet(item: $endFast) { FastEditor(mode: .finish($0)) }
        .sheet(isPresented: $showJournal) { JournalEditor(fastID: model.archive.active?.id) }
    }

    // MARK: Hero

    /// The ring and what's under it: a running fast, an eating window, or nothing running.
    @ViewBuilder private func hero(at now: Date) -> some View {
        if let fast = model.archive.active {
            let elapsed = fast.duration(at: now)
            let target = Double(fast.goalMinutes ?? 0) * 60
            let reached = target > 0 && elapsed >= target
            let showRemaining = remaining && target > 0 && !reached
            VStack(spacing: Tokens.Space.l) {
                ring(progress: target > 0 ? elapsed / target : 1, tint: Tokens.dataWarm,
                     label: reached ? f("Goal reached") : showRemaining ? f("Remaining") : f("Fasting"),
                     value: showRemaining ? max(0, target - elapsed) : elapsed,
                     caption: target > 0 ? f("Goal:") + " " + FastFormat.goal(fast.goalMinutes) : f("Open ended"),
                     toggles: target > 0 && !reached)
                timesRow(fast)
                if reached { Text(f("The timer keeps running until you end it.")).textRole(.caption).multilineTextAlignment(.center) }
            }
        } else if model.archive.settings.showEatingWindow, let last = model.archive.latestCompleted, let end = last.end,
                  last.eatingMinutes > 0, now < end.addingTimeInterval(Double(last.eatingMinutes) * 60) {
            let window = Double(last.eatingMinutes) * 60
            let finish = end.addingTimeInterval(window)
            VStack(spacing: Tokens.Space.m) {
                ring(progress: now.timeIntervalSince(end) / window, tint: Tokens.muted,
                     label: f("Eating window"), value: finish.timeIntervalSince(now),
                     caption: f("Ends at") + " " + finish.formatted(date: .omitted, time: .shortened), toggles: false)
                Text(f("A reminder, not a deadline. Start your next fast when it suits you.")).textRole(.caption)
                    .multilineTextAlignment(.center)
            }
        } else if textSize.isAccessibilitySize {
            Text(f("No fast running")).textRole(.title)
        } else {
            ZStack {
                Circle().stroke(Tokens.rule, lineWidth: ringWidth).padding(ringWidth / 2)
                VStack(spacing: Tokens.Space.s) {
                    if let last = model.archive.latestCompleted, let end = last.end {
                        Text(f("Last fast")).textRole(.caption, emphasized: true).textCase(.uppercase)
                        Text(FastFormat.duration(end.timeIntervalSince(last.start))).textRole(.hero).numeric()
                            .minimumScaleFactor(0.6).lineLimit(1)
                        Text(end.formatted(.relative(presentation: .named))).textRole(.secondary)
                    } else {
                        Image(systemName: "sun.horizon").glyph()
                        Text(f("At your own pace.")).textRole(.section)
                        Text(f("A little structure. Room for real life.")).textRole(.secondary)
                    }
                }.multilineTextAlignment(.center).padding(Tokens.Space.xl)
            }
            .frame(maxWidth: ringSize).aspectRatio(1, contentMode: .fit)
            .accessibilityElement(children: .combine)
        }
    }

    private var ringWidth: CGFloat { 18 }
    private var ringSize: CGFloat { 300 }

    /// A progress ring with a live time in the middle. At accessibility text sizes the ring steps
    /// aside and the words stack, so nothing scales down to fit.
    @ViewBuilder private func ring(progress: Double, tint: Color, label: String, value: TimeInterval, caption: String, toggles: Bool) -> some View {
        let center = VStack(spacing: Tokens.Space.xs) {
            Text(label).textRole(.caption, emphasized: true).textCase(.uppercase)
            Text(clock(value)).textRole(.display).numeric().lineLimit(1).minimumScaleFactor(0.5)
                .accessibilityLabel(FastFormat.duration(value))
                .accessibilityIdentifier("fast.elapsed")
            Text(caption).textRole(.secondary)
        }.multilineTextAlignment(.center)
        Group {
            if textSize.isAccessibilitySize {
                VStack(spacing: Tokens.Space.m) {
                    center
                    Capsule().fill(Tokens.rule).frame(height: Tokens.Space.s)
                        .overlay(alignment: .leading) {
                            GeometryReader { geometry in
                                Capsule().fill(tint).frame(width: geometry.size.width * min(1, max(0, progress)))
                            }
                        }
                        .accessibilityHidden(true)
                }
            } else {
                ZStack {
                    ProgressRing(progress: progress, tint: tint, width: ringWidth)
                    center.padding(Tokens.Space.xl)
                }.frame(maxWidth: ringSize).aspectRatio(1, contentMode: .fit)
            }
        }
        .contentShape(Circle())
        .onTapGesture { if toggles { withAnimation(reduceMotion ? nil : .easeInOut) { remaining.toggle() } } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(toggles ? .isButton : [])
        .accessibilityHint(toggles ? (remaining ? f("Shows elapsed time") : f("Shows time remaining")) : "")
    }

    /// Started and goal times side by side, each a way into the editor.
    private func timesRow(_ fast: FastRecord) -> some View {
        let layout = textSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: Tokens.Space.m)) : AnyLayout(HStackLayout(spacing: Tokens.Space.m))
        return layout {
            timeCell(f("Started"), fast.start, identifier: "fast.editActive") { editFast = fast }
            if let goal = fast.goalDate {
                timeCell(f("Goal"), goal, identifier: "fast.editGoal") { editFast = fast }
            }
        }
    }

    private func timeCell(_ title: String, _ date: Date, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: Tokens.Space.xs) {
                Text(title).textRole(.caption, emphasized: true).textCase(.uppercase)
                Text(date.formatted(.dateTime.weekday(.abbreviated).hour().minute())).textRole(.rowTitle, emphasized: true).numeric()
                Text(f("Edit")).textRole(.caption).foregroundStyle(.tint)
            }
            .frame(maxWidth: .infinity).padding(.vertical, Tokens.Space.ms)
            .background(Tokens.card, in: RoundedRectangle(cornerRadius: Tokens.Radius.card))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title + ", " + date.formatted(date: .abbreviated, time: .shortened))
        .accessibilityHint(f("Edit start or goal"))
        .accessibilityIdentifier(identifier)
    }

    /// One large button for the one thing to do next.
    @ViewBuilder private var actions: some View {
        VStack(spacing: Tokens.Space.ms) {
            if let active = model.archive.active {
                Button { endFast = active } label: {
                    Text(f("End fast")).textRole(.rowTitle, emphasized: true).frame(maxWidth: .infinity)
                }
                .sevenProminentButton().controlSize(.extraLarge).buttonBorderShape(.capsule)
                .accessibilityIdentifier("fast.end")
            } else {
                GatedButton(feature: f("Start a fast"), action: { showStart = true }) {
                    Label(f("Start a fast"), systemImage: "play.fill").textRole(.rowTitle, emphasized: true).frame(maxWidth: .infinity)
                }
                .sevenProminentButton().controlSize(.extraLarge).buttonBorderShape(.capsule)
                .accessibilityIdentifier("fast.start")
                NavigationLink { PlansScreen() } label: { Label(f("Plans & presets"), systemImage: "calendar.badge.clock") }
                    .foregroundStyle(.tint)
            }
        }.frame(maxWidth: 420)
    }

    /// 1:05:09, the way a timer reads. Hours keep counting past a day.
    private func clock(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds))
        return Duration.seconds(total).formatted(.time(pattern: .hourMinuteSecond(padHourToLength: 1)))
    }
}

/// A round progress ring: a faint track, the arc in a data hue with round ends, and a dot where it
/// stops. Past the goal the arc stays full.
struct ProgressRing: View {
    var progress: Double
    var tint: Color
    var width: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        let value = min(1, max(0, progress))
        ZStack {
            Circle().stroke(Tokens.rule, lineWidth: width)
            Circle().trim(from: 0, to: value)
                .stroke(tint, style: StrokeStyle(lineWidth: width, lineCap: .round))
                .rotationEffect(.degrees(-90))
            GeometryReader { geometry in
                let radius = min(geometry.size.width, geometry.size.height) / 2
                let angle = Angle.degrees(value * 360 - 90)
                Circle().fill(Tokens.card).overlay(Circle().stroke(tint, lineWidth: width / 4))
                    .frame(width: width * 0.9, height: width * 0.9)
                    .position(x: geometry.size.width / 2 + radius * cos(angle.radians),
                              y: geometry.size.height / 2 + radius * sin(angle.radians))
            }
        }
        .padding(width / 2)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: value)
        .accessibilityHidden(true)
    }
}
