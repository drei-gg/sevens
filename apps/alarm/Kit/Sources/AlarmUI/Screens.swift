import SwiftUI
import AVFAudio
import SevenKit
import AlarmCore

public struct AlarmRootView: View {
    @Bindable var model: AlarmModel
    @State private var before = false
    public init(model: AlarmModel) { self.model = model }
    public var body: some View {
        Group {
            if model.ready {
                TabView {
                    NavigationStack { RoutinesScreen(kind: .alarm) }.tabItem { Label("Alarms", systemImage: "alarm") }
                    NavigationStack { RoutinesScreen(kind: .loop) }.tabItem { Label("Loops", systemImage: "repeat") }
                    NavigationStack { HistoryScreen() }.tabItem { Label("History", systemImage: "calendar") }
                    NavigationStack { SettingsScreen() }.tabItem { Label("Settings", systemImage: "gearshape") }
                }.sevenAdaptiveTabs()
            } else if let message = model.loadError {
                ContentUnavailableView { Label("Couldn’t open your records", systemImage: "externaldrive.badge.exclamationmark") } description: { Text(message) } actions: { Button("Try again") { Task { await model.reload() } } }
            } else { ProgressView("Opening alarms…") }
        }
        .sevenLook()
        .toggleStyle(SystemSwitchStyle())
        .task(id: model.store.hasStarted) { if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { before = true } }
        .sheet(isPresented: $before) { BeforeYouBuyView(content: AlarmGate.before) { _ in before = false }.interactiveDismissDisabled() }
        .sheet(item: $model.previewMission) { MissionScreen(routine: $0, row: nil) }
        .fullScreenCover(item: $model.mission) { MissionScreen(routine: $0.routine, row: $0).interactiveDismissDisabled() }
        .alert("Needs attention", isPresented: Binding(get: { model.error != nil }, set: { shown in if !shown { model.error = nil } })) { Button("OK", role: .cancel) { model.error = nil } } message: { Text(model.error ?? "") }
        .environment(model).sevenStore(model.store, unlock: AlarmGate.unlock)
        // Expiry is presented in Settings, never over a ringing alarm or mission.
    }
}
enum AlarmGate {
    static var unlock: UnlockContent { UnlockContent(appName: "Alarm & Loops", features: ["Wake-up missions and follow-up checks", "Shared times with different weekdays", "Interval loops and complete local history", "Portable backups and exports"]) }
    static var before: BeforeYouBuyContent { BeforeYouBuyContent(title: "Your iPhone already has alarms.", paragraphs: [.init("Clock already wakes you up and times things. If that is enough, keep your money."), .init("This adds wake-up missions, flexible time loops and a history you can keep. Try everything for 7 days, then {price}, once. Nothing renews.", withoutPrice: "This adds wake-up missions, flexible time loops and a history you can keep. Try everything for 7 days, then a one-time purchase."), .init("Existing enabled alarms keep running after the trial. You can always stop alarms and read, export or delete your history.")], justScanLabel: "Look around first") }
}
func clockText(_ minute: Int32) -> String {
    let formatter = DateFormatter()
    formatter.locale = .autoupdatingCurrent
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateStyle = .none; formatter.timeStyle = .short
    return formatter.string(from: Date(timeIntervalSince1970: Double(minute) * 60))
}
func dayText(_ weekdays: [Int32]) -> String { let symbols = Calendar.current.shortWeekdaySymbols; return weekdays.sorted().map { symbols[Int($0) - 1] }.joined(separator: " · ") }

/// The one filled button on a screen: an ink capsule, full width up to a comfortable measure.
struct PrimaryButton: View {
    let title: LocalizedStringKey
    var systemImage: String?
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Group {
                if let systemImage { Label(title, systemImage: systemImage) } else { Text(title) }
            }
            .textRole(.rowTitle, emphasized: true).frame(maxWidth: .infinity)
        }
        .sevenProminentButton().controlSize(.extraLarge).buttonBorderShape(.capsule)
        .frame(maxWidth: 420)
    }
}

/// Seven round day buttons: the days a time rings are filled ink, the rest outlined.
struct WeekdayPicker: View {
    @Binding var days: [Int32]
    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Tokens.Space.xs) { ForEach(WeekOrder.days, id: \.self) { day in dayButton(day) } }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 56), spacing: Tokens.Space.s)], spacing: Tokens.Space.s) { ForEach(WeekOrder.days, id: \.self) { day in dayButton(day) } }
            }
            Menu("Choose days") {
                Button("Every day") { days = [1, 2, 3, 4, 5, 6, 7] }
                Button("Weekdays") { days = [2, 3, 4, 5, 6] }
                Button("Weekends") { days = [1, 7] }
            }.buttonStyle(.borderless)
        }
        .sensoryFeedback(.selection, trigger: days)
    }
    func dayButton(_ day: Int) -> some View {
        let value = Int32(day), on = days.contains(value)
        return Button { if on { if days.count > 1 { days.removeAll { $0 == value } } } else { days.append(value) } } label: {
            Text(Calendar.current.veryShortWeekdaySymbols[day - 1])
                .foregroundStyle(on ? Tokens.onInk : Color.primary)
                .textRole(.body, emphasized: on)
                .frame(maxWidth: .infinity, minHeight: 40)
                .aspectRatio(1, contentMode: .fit)
                .background { Circle().fill(on ? Tokens.ink : Color.clear) }
                .overlay { Circle().stroke(on ? Color.clear : Tokens.rule, lineWidth: 1) }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Calendar.current.weekdaySymbols[day - 1])
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
struct MinutePicker: View {
    let title: String
    @Binding var minute: Int32
    var wheel = false
    var body: some View {
        let picker = DatePicker(title, selection: Binding(get: { Calendar.current.startOfDay(for: Date()).addingTimeInterval(Double(minute) * 60) }, set: { minute = Int32(Calendar.current.component(.hour, from: $0) * 60 + Calendar.current.component(.minute, from: $0)) }), displayedComponents: .hourAndMinute)
        if wheel { picker.datePickerStyle(.wheel).labelsHidden().frame(maxWidth: .infinity) } else { picker }
    }
}

/// The alarm editor: the time first and big (a wheel, like Clock), its days under it, then the
/// name, the sound, the mission and snooze.
struct RoutineEditor: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var routine: Routine
    @State private var scanning = false
    @State private var preview = false
    @State private var saveError: String?
    @State private var tonePlayer: AVAudioPlayer?
    private var isNew: Bool { !model.archive.routines.contains { $0.id == routine.id } }
    var body: some View {
        NavigationStack {
            Form {
                timeSections
                Section {
                    TextField("Name", text: $routine.name).accessibilityIdentifier("routine-name")
                    TextField("Why get up? (optional)", text: Binding(get: { routine.reason ?? "" }, set: { routine.reason = $0 }), axis: .vertical)
                    TextField("Group (optional)", text: Binding(get: { routine.group ?? "" }, set: { routine.group = $0 }))
                    Picker("Timing", selection: $routine.timing) { Text("Weekly times").tag(Timing.weekly); Text("Once").tag(Timing.once); if routine.kind == .loop { Text("Interval window").tag(Timing.window) } }
                    if routine.kind == .loop { Picker("Delivery", selection: $routine.delivery) { Text("Quiet reminder").tag(Delivery.reminder); Text("Alarm").tag(Delivery.alarm) }.onChange(of: routine.delivery) { _, value in if value == .reminder { routine.mission = .none; routine.followUpMinutes = 0 } } }
                } header: { Text("Routine").foregroundStyle(Tokens.muted) }
                if routine.delivery == .alarm { alarmSections }
                Section {
                    let result = try? ScheduleCompiler.occurrences(routine, after: Date())
                    if let result, !result.isEmpty {
                        ForEach(Array(result.prefix(12).enumerated()), id: \.offset) { _, date in
                            LabeledContent(date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()), value: date.formatted(date: .omitted, time: .shortened)).numeric()
                        }
                        if result.count > 12 { Text("\(result.count - 12) more this week").textRole(.caption) }
                    } else { Text("No upcoming times. Check the schedule.").textRole(.secondary) }
                } header: { Text("Next seven days").foregroundStyle(Tokens.muted) } footer: {
                    Text("Clock times follow your current timezone. When the clocks change, the preview uses the next valid time and the first of a repeated time.").textRole(.caption)
                }
            }
            .sevenScreenBackground()
            .navigationTitle(isNew ? (routine.kind == .alarm ? "New alarm" : "New loop") : "Edit routine")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { Task { if await model.save(routine) { dismiss() } else { saveError = model.error; model.error = nil } } }.disabled(model.busy).accessibilityIdentifier("save-routine") }
            }
            .onDisappear { tonePlayer?.stop() }
            .sheet(isPresented: $scanning) { CodeScanner { routine.code = $0; scanning = false } }
            .sheet(isPresented: $preview) { MissionScreen(routine: routine, row: nil) }
            .alert("Couldn’t save", isPresented: Binding(get: { saveError != nil }, set: { shown in if !shown { saveError = nil } })) { Button("OK", role: .cancel) {} } message: { Text(saveError ?? "") }
        }
    }

    @ViewBuilder private var timeSections: some View {
        if routine.timing == .once {
            Section {
                DatePicker("Date and time", selection: $routine.once, in: Date()...).datePickerStyle(.wheel).labelsHidden().frame(maxWidth: .infinity)
            } header: { Text("Rings once").foregroundStyle(Tokens.muted) }
        } else if routine.timing == .weekly {
            ForEach($routine.slots) { $slot in
                Section {
                    MinutePicker(title: "Time", minute: $slot.minute, wheel: routine.slots.count == 1)
                    WeekdayPicker(days: $slot.weekdays)
                    if routine.slots.count > 1 { Button("Remove time", role: .destructive) { routine.slots.removeAll { $0.id == slot.id } } }
                } header: { Text("\(clockText(slot.minute)) · \(dayText(slot.weekdays))").foregroundStyle(Tokens.muted).numeric() }
            }
            Section {
                Button("Add another time", systemImage: "plus") { routine.slots.append(TimeSlot(minute: 720)) }.disabled(routine.slots.count >= 48)
            } footer: {
                Text("Each time rings on its own days. Turn Wednesday off for 7:00 without changing noon or 2:00.").textRole(.caption)
            }
        } else {
            Section {
                MinutePicker(title: "Start", minute: $routine.windowStart); MinutePicker(title: "End", minute: $routine.windowEnd)
                Stepper("Every \(ClockFormat.minutes(routine.intervalMinutes))", value: $routine.intervalMinutes, in: 5...720, step: 5)
                WeekdayPicker(days: $routine.windowDays)
            } header: { Text("Time window").foregroundStyle(Tokens.muted) } footer: {
                Text("Overnight windows belong to the day they start. The end is included only when an interval lands on it.").textRole(.caption)
            }
        }
    }

    @ViewBuilder private var alarmSections: some View {
        Section {
            AlarmTonePicker(tone: Binding(get: { routine.tone ?? .system }, set: { routine.tone = $0 }))
            if let tone = routine.tone, tone != .system {
                Button("Preview tone", systemImage: "play.circle") {
                    do { guard let url = Bundle.main.url(forResource: "alarm-\(tone.rawValue)", withExtension: "wav") else { throw LoopFailure.invalid("This tone isn’t in this build.") }; tonePlayer = try AVAudioPlayer(contentsOf: url); tonePlayer?.play() }
                    catch { saveError = error.localizedDescription }
                }
            }
        } header: { Text("Sound").foregroundStyle(Tokens.muted) } footer: {
            Text("Preview uses media volume. The ringing alarm uses the system alarm volume; this app doesn’t override it.").textRole(.caption)
        }
        Section {
            Picker("Mission", selection: $routine.mission) {
                ForEach(MissionKind.allCases, id: \.self) { kind in Label(kind.title, systemImage: kind.symbol).tag(kind) }
            }
            if [.memory, .shake, .walk, .numberOrder, .colorWord].contains(routine.mission) {
                Picker("Difficulty", selection: Binding(get: { routine.difficulty }, set: { value in routine.challengeLevel = Int32(value) })) { Text("Easy").tag(1); Text("Medium").tag(2); Text("Hard").tag(3) }
            }
            if routine.mission == .arithmetic { Stepper(routine.mathCount == 1 ? "1 question" : "\(routine.mathCount) questions", value: $routine.mathCount, in: 1...10) }
            if routine.mission == .typing { TextField("Phrase", text: $routine.phrase) }
            if routine.mission == .code {
                Button(routine.code.isEmpty ? "Scan your code" : "Replace saved code", systemImage: "qrcode.viewfinder") { scanning = true }
                if !routine.code.isEmpty { Label("Code saved on this phone", systemImage: "checkmark.circle") }
            }
            if routine.mission != .none { Button("Preview mission", systemImage: "play.circle") { preview = true } }
        } header: { Text("Wake-up mission").foregroundStyle(Tokens.muted) } footer: {
            Text("iOS keeps its own Stop control. A mission records completion; it can’t prevent shutdown or system dismissal.").textRole(.caption)
        }
        Section {
            Stepper("Snooze: \(ClockFormat.minutes(routine.snoozeMinutes))", value: $routine.snoozeMinutes, in: 1...30)
            Stepper(routine.snoozeLimit == 0 ? "No snoozes" : "Snooze limit: \(routine.snoozeLimit)", value: $routine.snoozeLimit, in: 0...10)
            Stepper(routine.followUpMinutes == 0 ? "Wake-up check: off" : "Check again after \(ClockFormat.minutes(routine.followUpMinutes))", value: $routine.followUpMinutes, in: 0...30)
        } header: { Text("Snooze and wake-up check").foregroundStyle(Tokens.muted) } footer: {
            Text("Open the alarm to use its mission or snooze. A wake-up check rings once after completion or a system dismissal.").textRole(.caption)
        }
    }
}

/// The mission that wakes you: the routine and your reason big, the task in the middle, one
/// filled button to finish it, snooze and emergency stop quieter beneath.
struct MissionScreen: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let routine: Routine
    let row: Registration?
    @State private var challenge = ArithmeticChallenge()
    @State private var answer = ""
    @State private var solved = 0
    @State private var feedback = ""
    @State private var scanning = false
    @State private var emergency = false
    @State private var complete = false
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Tokens.Space.l) {
                    VStack(spacing: Tokens.Space.s) {
                        Image(systemName: complete ? "checkmark.circle" : "sun.max").textRole(.hero).imageScale(.large)
                            .foregroundStyle(Tokens.dataWarm).accessibilityHidden(true)
                        Text(complete ? "Done." : routine.name).textRole(.title).multilineTextAlignment(.center)
                        if !complete, let reason = routine.reason, !reason.isEmpty { Text(reason).textRole(.section).multilineTextAlignment(.center) }
                        if row == nil { Text("Mission preview. No alarm will ring.").textRole(.caption) }
                    }
                    if !complete {
                        mission
                        if !feedback.isEmpty { Text(feedback).textRole(.secondary).multilineTextAlignment(.center).accessibilityIdentifier("mission-feedback") }
                        VStack(spacing: Tokens.Space.ms) {
                            if let row, row.snoozes < routine.snoozeLimit {
                                Button { Task { if await model.finish(row, kind: .snoozed) { dismiss() } else { feedback = model.error ?? "Couldn’t snooze."; model.error = nil } } } label: {
                                    Label("Snooze \(ClockFormat.minutes(routine.snoozeMinutes))", systemImage: "zzz").textRole(.rowTitle, emphasized: true).frame(maxWidth: .infinity)
                                }.quickAddChip().frame(maxWidth: 420)
                            }
                            if row != nil { Button("Emergency stop", role: .destructive) { emergency = true } }
                        }
                    } else {
                        PrimaryButton(title: "Close") { dismiss() }
                    }
                }
                .padding(Tokens.Space.l).frame(maxWidth: 520).frame(maxWidth: .infinity)
            }
            .sevenScreenBackground()
            .navigationTitle(row == nil ? "Try mission" : "Wake up").navigationBarTitleDisplayMode(.inline)
            .toolbar { if row == nil { Button("Close") { dismiss() } } }
            .sheet(isPresented: $scanning) { CodeScanner { value in scanning = false; if MissionCheck.code(value, expected: routine.code) { finish() } else { feedback = "That’s a different code." } } }
            .confirmationDialog("Stop without completing the mission?", isPresented: $emergency, titleVisibility: .visible) { Button("Stop alarm", role: .destructive) { if let row { Task { if await model.finish(row, kind: .emergencyStop) { dismiss() } } } } }
            .sensoryFeedback(.success, trigger: complete)
        }
    }

    @ViewBuilder private var mission: some View {
        switch routine.mission {
        case .none: PrimaryButton(title: "I’m up") { finish() }
        case .arithmetic:
            VStack(spacing: Tokens.Space.m) {
                Text("Question \(solved + 1) of \(routine.mathCount)").textRole(.caption, emphasized: true).textCase(.uppercase).numeric()
                Text("\(challenge.left) + \(challenge.right)").textRole(.display).numeric()
                    .accessibilityLabel("\(challenge.left) plus \(challenge.right)")
                TextField("Answer", text: $answer).keyboardType(.numberPad).multilineTextAlignment(.center).textRole(.section).numeric()
                    .padding(Tokens.Space.ms).sevenCard().frame(maxWidth: 240)
                    .accessibilityIdentifier("mission-answer")
                PrimaryButton(title: "Check answer") {
                    if challenge.accepts(answer) { solved += 1; answer = ""; feedback = ""; if solved >= Int(routine.mathCount) { finish() } else { challenge = ArithmeticChallenge() } } else { feedback = "Try that one again." }
                }
            }
        case .typing:
            VStack(spacing: Tokens.Space.m) {
                Text(routine.phrase).textRole(.section).multilineTextAlignment(.center).textSelection(.disabled)
                TextField("Type the phrase", text: $answer, axis: .vertical).autocorrectionDisabled().textInputAutocapitalization(.never)
                    .padding(Tokens.Space.ms).sevenCard()
                PrimaryButton(title: "Check phrase") { if MissionCheck.phrase(answer, expected: routine.phrase) { finish() } else { feedback = "The phrase doesn’t match yet." } }
            }
        case .memory: MemoryMission(level: routine.difficulty, onComplete: finish)
        case .shake: MovementMission(mode: .shake, target: routine.difficulty * 10, onComplete: finish)
        case .walk: MovementMission(mode: .walk, target: routine.difficulty * 20, onComplete: finish)
        case .numberOrder: NumberOrderMission(level: routine.difficulty, onComplete: finish)
        case .colorWord: ColorWordMission(level: routine.difficulty, onComplete: finish)
        case .code:
            VStack(spacing: Tokens.Space.m) {
                Image(systemName: "qrcode.viewfinder").glyph()
                Text("Go to your saved code and scan it.").textRole(.body).multilineTextAlignment(.center)
                PrimaryButton(title: "Scan code", systemImage: "qrcode.viewfinder") { scanning = true }
            }
        }
    }

    func finish() { if let row { Task { if await model.finish(row, kind: .completed) { dismiss() } else { feedback = model.error ?? "Couldn’t complete."; model.error = nil } } } else { complete = true } }
}
