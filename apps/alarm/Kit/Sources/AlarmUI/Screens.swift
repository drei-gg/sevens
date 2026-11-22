import SwiftUI
import AVFAudio
import UniformTypeIdentifiers
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
                    NavigationStack { RoutinesScreen(kind: .alarm) }.tabItem { Label(f("Alarms"), systemImage: "alarm") }
                    NavigationStack { RoutinesScreen(kind: .loop) }.tabItem { Label(f("Loops"), systemImage: "repeat") }
                    NavigationStack { HistoryScreen() }.tabItem { Label(f("History"), systemImage: "calendar") }
                    NavigationStack { SettingsScreen() }.tabItem { Label(f("Settings"), systemImage: "gearshape") }
                }.sevenAdaptiveTabs()
            } else if let message = model.loadError {
                ContentUnavailableView { Label(f("Couldn’t open your records"), systemImage: "externaldrive.badge.exclamationmark") } description: { Text(message) } actions: { Button(f("Try again")) { Task { await model.reload() } } }
            } else { ProgressView(f("Opening alarms…")) }
        }
        .sevenLook()
        .toggleStyle(SystemSwitchStyle())
        .task(id: model.store.hasStarted) { if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { before = true } }
        .sheet(isPresented: $before) { BeforeYouBuyView(content: AlarmGate.before) { _ in before = false }.interactiveDismissDisabled() }
        .sheet(item: $model.previewMission) { MissionScreen(routine: $0, row: nil) }
        .fullScreenCover(item: $model.mission) { MissionScreen(routine: $0.routine, row: $0, progress: model.progress).interactiveDismissDisabled() }
        .alert(f("Needs attention"), isPresented: Binding(get: { model.error != nil }, set: { shown in if !shown { model.error = nil } })) { Button(f("OK"), role: .cancel) { model.error = nil } } message: { Text(model.error ?? "") }
        .environment(model).sevenStore(model.store, unlock: AlarmGate.unlock)
        // Expiry is presented in Settings, never over a ringing alarm or mission.
    }
}
enum AlarmGate {
    static var unlock: UnlockContent { UnlockContent(appName: "Alarm & Loops", features: [f("Wake-up missions and follow-up checks"), f("Shared times with different weekdays"), f("Interval loops and complete local history"), f("Portable backups and exports")]) }
    static var before: BeforeYouBuyContent { BeforeYouBuyContent(title: f("Your iPhone already has alarms."), paragraphs: [.init(f("Clock already wakes you up and times things. If that is enough, keep your money.")), .init(f("This adds wake-up missions, flexible time loops and a history you can keep. Try everything for 7 days, then {price}, once. Nothing renews."), withoutPrice: f("This adds wake-up missions, flexible time loops and a history you can keep. Try everything for 7 days, then a one-time purchase.")), .init(f("Existing enabled alarms keep running after the trial. You can always stop alarms and read, export or delete your history."))], justScanLabel: f("Look around first")) }
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
    let title: String
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
            Menu(f("Choose days")) {
                Button(f("Every day")) { days = [1, 2, 3, 4, 5, 6, 7] }
                Button(f("Weekdays")) { days = [2, 3, 4, 5, 6] }
                Button(f("Weekends")) { days = [1, 7] }
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
/// A clock time stored as minutes after midnight, as a date the time picker can show. Built from the
/// hour and minute, not the start of the day plus an offset, so a day when the clocks change shows the
/// right time; a time that doesn't exist today (a spring-forward gap) is shown on the next or previous
/// day, where it does.
enum ClockMinute {
    static func date(for minute: Int32, near day: Date = Date(), calendar: Calendar = .current) -> Date {
        let hour = Int(minute) / 60, minutes = Int(minute) % 60
        for offset in [0, 1, -1] {
            guard let base = calendar.date(byAdding: .day, value: offset, to: day),
                  let date = calendar.date(bySettingHour: hour, minute: minutes, second: 0, of: base),
                  self.minute(of: date, calendar: calendar) == minute else { continue }
            return date
        }
        return calendar.startOfDay(for: day).addingTimeInterval(Double(minute) * 60)
    }
    static func minute(of date: Date, calendar: Calendar = .current) -> Int32 {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return Int32((parts.hour ?? 0) * 60 + (parts.minute ?? 0))
    }
}
struct MinutePicker: View {
    let title: String
    @Binding var minute: Int32
    var wheel = false
    var body: some View {
        let picker = DatePicker(title, selection: Binding(get: { ClockMinute.date(for: minute) }, set: { minute = ClockMinute.minute(of: $0) }), displayedComponents: .hourAndMinute)
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
    @State private var soundPreview = SoundPreview()
    @State private var choosingSound = false
    @State private var importingSound = false
    @State private var importTask: Task<Void, Never>?
    private var isNew: Bool { !model.archive.routines.contains { $0.id == routine.id } }
    var body: some View {
        NavigationStack {
            Form {
                timeSections
                Section {
                    TextField(f("Name"), text: $routine.name).accessibilityIdentifier("routine-name")
                    TextField(f("Why get up? (optional)"), text: Binding(get: { routine.reason ?? "" }, set: { routine.reason = $0 }), axis: .vertical)
                    TextField(f("Group (optional)"), text: Binding(get: { routine.group ?? "" }, set: { routine.group = $0 }))
                    Picker(f("Timing"), selection: $routine.timing) { Text(f("Weekly times")).tag(Timing.weekly); Text(f("Once")).tag(Timing.once); if routine.kind == .loop { Text(f("Interval window")).tag(Timing.window) } }
                    if routine.kind == .loop { Picker(f("Delivery"), selection: $routine.delivery) { Text(f("Quiet reminder")).tag(Delivery.reminder); Text(f("Alarm")).tag(Delivery.alarm) }.onChange(of: routine.delivery) { _, value in if value == .reminder { routine.mission = .none; routine.followUpMinutes = 0 } } }
                } header: { Text(f("Routine")).foregroundStyle(Tokens.muted) }
                if routine.delivery == .alarm { alarmSections }
                Section {
                    let result = try? ScheduleCompiler.occurrences(routine, after: Date())
                    if let result, !result.isEmpty {
                        ForEach(Array(result.prefix(12).enumerated()), id: \.offset) { _, date in
                            LabeledContent(date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()), value: date.formatted(date: .omitted, time: .shortened)).numeric()
                        }
                        if result.count > 12 { Text("\(result.count - 12) more this week").textRole(.caption) }
                    } else { Text(f("No upcoming times. Check the schedule.")).textRole(.secondary) }
                } header: { Text(f("Next seven days")).foregroundStyle(Tokens.muted) } footer: {
                    Text(f("Clock times follow your current timezone. When the clocks change, the preview uses the next valid time and the first of a repeated time.")).textRole(.caption)
                }
            }
            .sevenScreenBackground()
            .navigationTitle(isNew ? (routine.kind == .alarm ? f("New alarm") : f("New loop")) : f("Edit routine"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(f("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(f("Save")) { Task { if await model.save(routine) { dismiss() } else { saveError = model.error; model.error = nil } } }.disabled(model.busy || importingSound).accessibilityIdentifier("save-routine") }
            }
            .onDisappear {
                soundPreview.stop()
                if !choosingSound && !scanning && !preview { importTask?.cancel() }
            }
            .fileImporter(isPresented: $choosingSound, allowedContentTypes: [.wav]) { result in
                do {
                    let url = try result.get()
                    importingSound = true
                    importTask = Task {
                        defer { importingSound = false }
                        do {
                            let sound = try await Task.detached(priority: .userInitiated) { try ImportedSounds.live.load(url) }.value
                            guard !Task.isCancelled else { ImportedSounds.live.discard(sound, unlessUsedBy: model.archive); return }
                            soundPreview.stop(); routine.importedSound = sound
                        } catch { if !Task.isCancelled { saveError = error.localizedDescription } }
                    }
                } catch let error as CocoaError where error.code == .userCancelled { }
                catch { saveError = error.localizedDescription }
            }
            .sheet(isPresented: $scanning) { CodeScanner { routine.code = $0; scanning = false } }
            .sheet(isPresented: $preview) { MissionScreen(routine: routine, row: nil) }
            .alert(f("Couldn’t save"), isPresented: Binding(get: { saveError != nil }, set: { shown in if !shown { saveError = nil } })) { Button(f("OK"), role: .cancel) {} } message: { Text(saveError ?? "") }
        }
    }

    @ViewBuilder private var timeSections: some View {
        if routine.timing == .once {
            Section {
                DatePicker(f("Date and time"), selection: $routine.once, in: Date()...).datePickerStyle(.wheel).labelsHidden().frame(maxWidth: .infinity)
            } header: { Text(f("Rings once")).foregroundStyle(Tokens.muted) }
        } else if routine.timing == .weekly {
            ForEach($routine.slots) { $slot in
                Section {
                    MinutePicker(title: f("Time"), minute: $slot.minute, wheel: routine.slots.count == 1)
                    WeekdayPicker(days: $slot.weekdays)
                    if routine.slots.count > 1 { Button(f("Remove time"), role: .destructive) { routine.slots.removeAll { $0.id == slot.id } } }
                } header: { Text("\(clockText(slot.minute)) · \(dayText(slot.weekdays))").foregroundStyle(Tokens.muted).numeric() }
            }
            Section {
                Button(f("Add another time"), systemImage: "plus") { routine.slots.append(TimeSlot(minute: 720)) }.disabled(routine.slots.count >= 48)
            } footer: {
                Text(f("Each time rings on its own days. Turn Wednesday off for 7:00 without changing noon or 2:00.")).textRole(.caption)
            }
        } else {
            Section {
                MinutePicker(title: f("Start"), minute: $routine.windowStart); MinutePicker(title: f("End"), minute: $routine.windowEnd)
                Stepper("Every \(ClockFormat.minutes(routine.intervalMinutes))", value: $routine.intervalMinutes, in: 5...720, step: 5)
                WeekdayPicker(days: $routine.windowDays)
            } header: { Text(f("Time window")).foregroundStyle(Tokens.muted) } footer: {
                Text(f("Overnight windows belong to the day they start. The end is included only when an interval lands on it.")).textRole(.caption)
            }
        }
    }

    @ViewBuilder private var alarmSections: some View {
        Section {
            if routine.importedSound != nil || (routine.tone != nil && routine.tone != .system) {
                Button(soundPreview.playing ? f("Stop preview") : (routine.importedSound != nil ? f("Preview imported sound") : f("Preview tone")), systemImage: soundPreview.playing ? "stop.circle" : "play.circle") {
                    if soundPreview.playing { soundPreview.stop(); return }
                    do {
                        soundPreview.stop()
                        let url: URL
                        if let sound = routine.importedSound { url = ImportedSounds.live.file(sound) }
                        else if let tone = routine.tone, let bundled = Bundle.main.url(forResource: "alarm-\(tone.rawValue)", withExtension: "wav") { url = bundled }
                        else { throw LoopFailure.invalid(f("This tone isn’t in this build.")) }
                        try soundPreview.play(url)
                    }
                    catch { saveError = error.localizedDescription }
                }
            }
            if let sound = routine.importedSound {
                Text(sound.name).accessibilityIdentifier("imported-sound")
                if !ImportedSounds.live.available(sound) { Text(f("This audio file is missing. The fallback tone will play; import it again to restore your sound.")).foregroundStyle(Tokens.danger) }
                Button(f("Use built-in tone")) { soundPreview.stop(); routine.importedSound = nil }
            }
            Button(routine.importedSound == nil ? f("Import WAV sound") : f("Replace sound"), systemImage: "waveform") { soundPreview.stop(); choosingSound = true }.disabled(importingSound).accessibilityIdentifier("import-sound")
            if importingSound { ProgressView(f("Importing sound…")) }
            if routine.importedSound != nil { Text(f("Built-in fallback")).textRole(.caption).foregroundStyle(Tokens.muted) }
            AlarmTonePicker(tone: Binding(get: { routine.tone ?? .system }, set: { routine.tone = $0; soundPreview.stop() }))
            Text(f("16-bit PCM WAV, mono or stereo, 8–48 kHz, up to 29 seconds and 6 MB. JSON backups and templates do not include audio. Missing audio uses the fallback tone.")).textRole(.caption).foregroundStyle(Tokens.muted)
        } header: { Text(f("Sound")).foregroundStyle(Tokens.muted) } footer: {
            Text(f("Preview uses media volume. The ringing alarm uses the system alarm volume; this app doesn’t override it.")).textRole(.caption)
        }
        Section {
            Picker(f("Mission"), selection: $routine.mission) {
                ForEach(MissionKind.allCases, id: \.self) { kind in Label(kind.title, systemImage: kind.symbol).tag(kind) }
            }
            if [.memory, .shake, .walk, .numberOrder, .colorWord].contains(routine.mission) {
                Picker(f("Difficulty"), selection: Binding(get: { routine.difficulty }, set: { value in routine.challengeLevel = Int32(value) })) { Text(f("Easy")).tag(1); Text(f("Medium")).tag(2); Text(f("Hard")).tag(3) }
            }
            if routine.mission == .arithmetic { Stepper(String(localized: "\(Int(routine.mathCount)) questions", bundle: .module), value: $routine.mathCount, in: 1...10) }
            if routine.mission == .typing { TextField(f("Phrase"), text: $routine.phrase) }
            if routine.mission == .code {
                Button(routine.code.isEmpty ? f("Scan your code") : f("Replace saved code"), systemImage: "qrcode.viewfinder") { scanning = true }
                if !routine.code.isEmpty { Label(f("Code saved on this phone"), systemImage: "checkmark.circle") }
            }
            if routine.mission != .none { Button(f("Preview mission"), systemImage: "play.circle") { preview = true } }
        } header: { Text(f("Wake-up mission")).foregroundStyle(Tokens.muted) } footer: {
            Text(f("iOS keeps its own Stop control. A mission records completion; it can’t prevent shutdown or system dismissal.")).textRole(.caption)
        }
        Section {
            Stepper("Snooze: \(ClockFormat.minutes(routine.snoozeMinutes))", value: $routine.snoozeMinutes, in: 1...30)
            Stepper(routine.snoozeLimit == 0 ? f("No snoozes") : "Snooze limit: \(routine.snoozeLimit)", value: $routine.snoozeLimit, in: 0...10)
            Stepper(routine.followUpMinutes == 0 ? f("Wake-up check: off") : "Check again after \(ClockFormat.minutes(routine.followUpMinutes))", value: $routine.followUpMinutes, in: 0...30)
        } header: { Text(f("Snooze and wake-up check")).foregroundStyle(Tokens.muted) } footer: {
            Text(f("Open the alarm to use its mission or snooze. A wake-up check rings once after completion or a system dismissal.")).textRole(.caption)
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
    /// Steps already done in this ringing's mission, saved as they happen so reopening it resumes
    /// here. A preview keeps none.
    @State private var progress: MissionProgress?
    @State private var challenge = ArithmeticChallenge()
    @State private var answer = ""
    @State private var solved: Int
    @State private var feedback = ""
    @State private var scanning = false
    @State private var emergency = false
    @State private var complete = false
    init(routine: Routine, row: Registration?, progress: MissionProgress? = nil) {
        self.routine = routine; self.row = row
        let start = row.map { progress ?? missionProgressStart(row: $0) }
        _progress = State(initialValue: start)
        // The last question is always answered here, so reopening never completes the mission.
        _solved = State(initialValue: min(Int(start?.solved ?? 0), max(0, Int(routine.mathCount) - 1)))
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Tokens.Space.l) {
                    VStack(spacing: Tokens.Space.s) {
                        Image(systemName: complete ? "checkmark.circle" : "sun.max").textRole(.hero).imageScale(.large)
                            .foregroundStyle(Tokens.dataWarm).accessibilityHidden(true)
                        Text(complete ? f("Done.") : routine.name).textRole(.title).multilineTextAlignment(.center)
                        if !complete, let reason = routine.reason, !reason.isEmpty { Text(reason).textRole(.section).multilineTextAlignment(.center) }
                        if row == nil { Text(f("Mission preview. No alarm will ring.")).textRole(.caption) }
                    }
                    if !complete {
                        mission
                        if !feedback.isEmpty { Text(feedback).textRole(.secondary).multilineTextAlignment(.center).accessibilityIdentifier("mission-feedback") }
                        VStack(spacing: Tokens.Space.ms) {
                            if let row, row.snoozes < routine.snoozeLimit {
                                Button { Task { if await model.finish(row, kind: .snoozed) { dismiss() } else { feedback = model.error ?? f("Couldn’t snooze."); model.error = nil } } } label: {
                                    Label("Snooze \(ClockFormat.minutes(routine.snoozeMinutes))", systemImage: "zzz").textRole(.rowTitle, emphasized: true).frame(maxWidth: .infinity)
                                }.quickAddChip().frame(maxWidth: 420)
                            }
                            if row != nil { Button(f("Emergency stop"), role: .destructive) { emergency = true } }
                        }
                    } else {
                        PrimaryButton(title: f("Close")) { dismiss() }
                    }
                }
                .padding(Tokens.Space.l).frame(maxWidth: 520).frame(maxWidth: .infinity)
            }
            .sevenScreenBackground()
            .navigationTitle(row == nil ? f("Try mission") : f("Wake up")).navigationBarTitleDisplayMode(.inline)
            .toolbar { if row == nil { Button(f("Close")) { dismiss() } } }
            .sheet(isPresented: $scanning) { CodeScanner { value in scanning = false; if MissionCheck.code(value, expected: routine.code) { record { $0.matched = true }; finish() } else { feedback = f("That’s a different code.") } } }
            .confirmationDialog(f("Stop without completing the mission?"), isPresented: $emergency, titleVisibility: .visible) {
                // The root alert can't show over this full-screen mission, so a failure shows here.
                Button(f("Stop alarm"), role: .destructive) { if let row { Task { if await model.finish(row, kind: .emergencyStop) { dismiss() } else { feedback = model.error ?? f("Couldn’t stop the alarm."); model.error = nil } } } }
            }
            .sensoryFeedback(.success, trigger: complete)
        }
    }

    @ViewBuilder private var mission: some View {
        switch routine.mission {
        case .none: PrimaryButton(title: f("I’m up")) { finish() }
        case .arithmetic:
            VStack(spacing: Tokens.Space.m) {
                Text("Question \(solved + 1) of \(routine.mathCount)").textRole(.caption, emphasized: true).textCase(.uppercase).numeric()
                Text("\(challenge.left) + \(challenge.right)").textRole(.display).numeric()
                    .accessibilityLabel("\(challenge.left) plus \(challenge.right)")
                TextField(f("Answer"), text: $answer).keyboardType(.numberPad).multilineTextAlignment(.center).textRole(.section).numeric()
                    .padding(Tokens.Space.ms).sevenCard().frame(maxWidth: 240)
                    .accessibilityIdentifier("mission-answer")
                PrimaryButton(title: f("Check answer")) {
                    if challenge.accepts(answer) {
                        solved += 1; answer = ""; feedback = ""
                        if solved >= Int(routine.mathCount) { finish() } else { let done = solved; record { $0.solved = Int32(done) }; challenge = ArithmeticChallenge() }
                    } else { feedback = f("Try that one again.") }
                }
            }
        case .typing:
            VStack(spacing: Tokens.Space.m) {
                Text(routine.phrase).textRole(.section).multilineTextAlignment(.center).textSelection(.disabled)
                TextField(f("Type the phrase"), text: $answer, axis: .vertical).autocorrectionDisabled().textInputAutocapitalization(.never)
                    .padding(Tokens.Space.ms).sevenCard()
                PrimaryButton(title: f("Check phrase")) { if MissionCheck.phrase(answer, expected: routine.phrase) { finish() } else { feedback = f("The phrase doesn’t match yet.") } }
            }
        case .memory: MemoryMission(level: routine.difficulty, saved: progress, record: record, onComplete: finish)
        case .shake: MovementMission(mode: .shake, target: routine.difficulty * 10, saved: progress, record: record, onComplete: finish)
        case .walk: MovementMission(mode: .walk, target: routine.difficulty * 20, saved: progress, record: record, onComplete: finish)
        case .numberOrder: NumberOrderMission(level: routine.difficulty, saved: progress, record: record, onComplete: finish)
        case .colorWord: ColorWordMission(level: routine.difficulty, saved: progress, record: record, onComplete: finish)
        case .code:
            VStack(spacing: Tokens.Space.m) {
                Image(systemName: progress?.matched == true ? "checkmark.circle" : "qrcode.viewfinder").glyph()
                if progress?.matched == true {
                    // Scanned before the mission was left; only recording the completion is left.
                    Text(f("Your saved code was already scanned.")).textRole(.body).multilineTextAlignment(.center)
                    PrimaryButton(title: f("Finish mission")) { finish() }
                } else {
                    Text(f("Go to your saved code and scan it.")).textRole(.body).multilineTextAlignment(.center)
                    PrimaryButton(title: f("Scan code"), systemImage: "qrcode.viewfinder") { scanning = true }
                }
            }
        }
    }

    /// Saves a step of the ringing's mission so reopening resumes from it. Previews keep nothing.
    func record(_ change: (inout MissionProgress) -> Void) {
        guard row != nil, var next = progress else { return }
        change(&next); progress = next
        Task { await model.saveProgress(next) }
    }

    func finish() { if let row { Task { if await model.finish(row, kind: .completed) { dismiss() } else { feedback = model.error ?? f("Couldn’t complete."); model.error = nil } } } else { complete = true } }
}
