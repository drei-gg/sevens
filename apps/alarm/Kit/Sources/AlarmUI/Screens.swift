import SwiftUI
import UniformTypeIdentifiers
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
                    NavigationStack { RoutinesScreen(kind:.alarm) }.tabItem { Label("Alarms",systemImage:"alarm") }
                    NavigationStack { RoutinesScreen(kind:.loop) }.tabItem { Label("Loops",systemImage:"repeat") }
                    NavigationStack { HistoryScreen() }.tabItem { Label("History",systemImage:"calendar") }
                    NavigationStack { SettingsScreen() }.tabItem { Label("Settings",systemImage:"gearshape") }
                }.sevenAdaptiveTabs()
            } else if let message = model.loadError {
                ContentUnavailableView { Label("Could not open your records",systemImage:"externaldrive.badge.exclamationmark") } description: { Text(message) } actions: { Button("Retry") { Task { await model.reload() } } }
            } else { ProgressView("Opening alarms…") }
        }
        .task(id:model.store.hasStarted) { if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { before = true } }
        .sheet(isPresented:$before) { BeforeYouBuyView(content:AlarmGate.before) { _ in before = false }.interactiveDismissDisabled() }
        .sheet(item:$model.previewMission) { MissionScreen(routine:$0,row:nil) }
        .fullScreenCover(item:$model.mission) { MissionScreen(routine:$0.routine,row:$0).interactiveDismissDisabled() }
        .alert("Needs attention",isPresented:Binding(get:{model.error != nil},set:{shown in if !shown { model.error = nil }})) { Button("OK",role:.cancel) { model.error = nil } } message: { Text(model.error ?? "") }
        .environment(model).sevenStore(model.store,unlock:AlarmGate.unlock)
        // Expiry is presented in Settings, never over a ringing alarm or mission.
    }
}
enum AlarmGate {
    static var unlock: UnlockContent { UnlockContent(appName:"Alarm & Loops",features:["Wake-up missions and follow-up checks","Shared times with different weekdays","Interval loops and complete local history","Portable backups and exports"]) }
    static var before: BeforeYouBuyContent { BeforeYouBuyContent(title:"Your iPhone already has alarms.",paragraphs:[.init("Clock already wakes you up and times things. If that is enough, keep your money."),.init("This adds wake-up missions, flexible time loops and a history you can keep. Try everything for 7 days, then {price}, once. Nothing renews.",withoutPrice:"This adds wake-up missions, flexible time loops and a history you can keep. Try everything for 7 days, then a one-time purchase."),.init("Existing enabled alarms keep running after the trial. You can always stop alarms and read, export or delete your history.")],justScanLabel:"Look around first") }
}
func clockText(_ minute: Int) -> String {
    let formatter = DateFormatter()
    formatter.locale = .autoupdatingCurrent
    formatter.timeZone = TimeZone(secondsFromGMT:0)
    formatter.dateStyle = .none; formatter.timeStyle = .short
    return formatter.string(from:Date(timeIntervalSince1970:Double(minute)*60))
}
func dayText(_ weekdays: [Int]) -> String { let symbols = Calendar.current.shortWeekdaySymbols; return weekdays.sorted().map { symbols[$0-1] }.joined(separator:" · ") }
struct RoutinesScreen: View {
    @Environment(AlarmModel.self) private var model
    let kind: RoutineKind
    @State private var editing: Routine?
    @State private var deleting: Routine?
    @State private var search = ""
    @State private var customNap = false
    var routines: [Routine] { model.archive.routines.filter { $0.kind == kind && (search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) || ($0.group ?? "").localizedCaseInsensitiveContains(search)) }.sorted {
        let left = (try? ScheduleCompiler.occurrences($0,after:Date()).first) ?? .distantFuture
        let right = (try? ScheduleCompiler.occurrences($1,after:Date()).first) ?? .distantFuture
        return left == right ? $0.name < $1.name : left < right
    } }
    var groups: [String] { Set(model.archive.routines.compactMap { let value = $0.group?.trimmingCharacters(in:.whitespacesAndNewlines) ?? ""; return value.isEmpty ? nil : value }).sorted() }
    var body: some View {
        List {
            if model.isPreview { Section { Label("Preview data · delivery disabled",systemImage:"eye").foregroundStyle(.secondary) } }
            Section {
                Text(model.scheduling).font(.footnote).foregroundStyle(.secondary)
                if !model.isPreview { Button("Enable alarms & reminders") { Task { await model.authorize() } } }
            }
            if kind == .alarm {
                Section("Quick nap") {
                    LazyVGrid(columns:[GridItem(.adaptive(minimum:100))]) { ForEach([15,20,30,60],id:\.self) { minutes in Button("\(minutes) min") { Task { await model.nap(minutes:minutes) } }.buttonStyle(.bordered).frame(maxWidth:.infinity,minHeight:44) } }
                    Button("Custom nap…") { customNap = true }.accessibilityIdentifier("custom-nap")
                }
            }
            if !groups.isEmpty {
                Section("Groups") {
                    ForEach(groups,id:\.self) { group in
                        Toggle(group,isOn:Binding(get:{ model.archive.routines.filter { $0.group?.trimmingCharacters(in:.whitespacesAndNewlines) == group }.allSatisfy(\.enabled) },set:{value in Task { await model.toggleGroup(group,enabled:value) }}))
                    }
                }
            }
            if model.archive.installed.contains(where:{$0.purpose == "followUp"}) {
                Section { Button("I'm already awake — cancel wake-up checks") { Task { await model.clearWakeUpChecks() } } }
            }
            if routines.isEmpty {
                ContentUnavailableView(kind == .alarm ? "Your next morning starts here" : "Keep the routine together",systemImage:kind == .alarm ? "alarm" : "repeat",description:Text(kind == .alarm ? "Add a wake-up time and an optional mission." : "Several times. Different weekdays. One routine."))
            }
            ForEach(routines) { routine in
                Section {
                    Toggle(isOn:Binding(get:{routine.enabled},set:{value in Task { await model.toggle(routine,enabled:value) }})) {
                        VStack(alignment:.leading,spacing:6) {
                            Text(routine.name).font(.headline)
                            if let group = routine.group, !group.isEmpty { Text(group).font(.caption).foregroundStyle(.secondary) }
                            if routine.timing == .once { Text(routine.once.formatted(date:.abbreviated,time:.shortened)).font(.title3.monospacedDigit()) }
                            else if routine.timing == .window { Text("\(clockText(routine.windowStart))–\(clockText(routine.windowEnd)) · every \(routine.intervalMinutes) min").font(.callout) }
                            else { Text(routine.slots.map { clockText($0.minute) }.joined(separator:"  ·  ")).font(.title3.monospacedDigit()) }
                            NextOccurrenceLabel(routine:routine)
                        }
                    }.accessibilityIdentifier("routine-\(routine.name)")
                    HStack {
                        Button("Edit") { editing = routine }
                        Button("Duplicate") { Task { await model.duplicate(routine) } }
                        Spacer()
                        if routine.mission != .none { Button("Try mission") { model.previewMission = routine } }
                        else { Button("Log done") { Task { await model.manual(routine,date:Date()) } } }
                    }.buttonStyle(.borderless)
                }.swipeActions { Button("Delete",role:.destructive) { deleting = routine } }
            }
        }
        .navigationTitle(kind == .alarm ? "Alarms" : "Time loops")
        .searchable(text:$search,prompt:"Find a routine or group")
        .toolbar { Button("Add",systemImage:"plus") { editing = model.newRoutine(kind:kind) }.accessibilityIdentifier("add-routine") }
        .sheet(item:$editing) { RoutineEditor(routine:$0) }
        .sheet(isPresented:$customNap) { CustomNapSheet() }
        .confirmationDialog("Delete this routine? History will stay.",isPresented:Binding(get:{deleting != nil},set:{if !$0 {deleting = nil}}),titleVisibility:.visible) {
            Button("Delete routine",role:.destructive) { if let r = deleting { Task { await model.delete(r) } }; deleting = nil }
        }
    }
}
struct WeekdayPicker: View {
    @Binding var days: [Int]
    var body: some View {
        let order = [2,3,4,5,6,7,1]
        VStack(alignment:.leading) {
            ViewThatFits(in:.horizontal) {
                HStack { ForEach(order,id:\.self) { day in dayButton(day) } }
                LazyVGrid(columns:[GridItem(.adaptive(minimum:70))]) { ForEach(order,id:\.self) { day in dayButton(day) } }
            }
            Menu("Choose days") {
                Button("Every day") { days = [1,2,3,4,5,6,7] }
                Button("Weekdays") { days = [2,3,4,5,6] }
                Button("Weekends") { days = [1,7] }
            }.buttonStyle(.borderless)
        }
    }
    func dayButton(_ day: Int) -> some View {
        Button { if days.contains(day) { if days.count > 1 { days.removeAll {$0 == day} } } else { days.append(day) } } label: {
            Text(Calendar.current.shortWeekdaySymbols[day-1]).font(.caption.weight(.semibold)).frame(maxWidth:.infinity,minHeight:40)
                .background(days.contains(day) ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.08),in:RoundedRectangle(cornerRadius:8))
        }.buttonStyle(.plain).accessibilityLabel(Calendar.current.weekdaySymbols[day-1]).accessibilityAddTraits(days.contains(day) ? .isSelected : [])
    }
}
struct MinutePicker: View {
    let title: String
    @Binding var minute: Int
    var body: some View {
        DatePicker(title,selection:Binding(get:{ Calendar.current.startOfDay(for:Date()).addingTimeInterval(Double(minute)*60) },set:{minute = Calendar.current.component(.hour,from:$0)*60 + Calendar.current.component(.minute,from:$0)}),displayedComponents:.hourAndMinute)
    }
}
struct RoutineEditor: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var routine: Routine
    @State private var scanning = false
    @State private var preview = false
    @State private var saveError: String?
    @State private var tonePlayer: AVAudioPlayer?
    var body: some View {
        NavigationStack {
            Form {
                Section("Routine") {
                    TextField("Name",text:$routine.name).accessibilityIdentifier("routine-name")
                    TextField("Why get up? (optional)",text:Binding(get:{routine.reason ?? ""},set:{routine.reason = $0}),axis:.vertical)
                    TextField("Group (optional)",text:Binding(get:{routine.group ?? ""},set:{routine.group = $0}))
                    Picker("Timing",selection:$routine.timing) { Text("Weekly times").tag(Timing.weekly); Text("Once").tag(Timing.once); if routine.kind == .loop { Text("Interval window").tag(Timing.window) } }
                    if routine.kind == .loop { Picker("Delivery",selection:$routine.delivery) { Text("Quiet reminder").tag(Delivery.reminder); Text("Alarm").tag(Delivery.alarm) }.onChange(of:routine.delivery) { _, value in if value == .reminder { routine.mission = .none; routine.followUpMinutes = 0 } } }
                }
                if routine.timing == .once { Section("When") { DatePicker("Date and time",selection:$routine.once,in:Date()...) } }
                else if routine.timing == .weekly {
                    ForEach($routine.slots) { $slot in
                        Section {
                            MinutePicker(title:"Shared time",minute:$slot.minute)
                            WeekdayPicker(days:$slot.weekdays)
                            if routine.slots.count > 1 { Button("Remove time",role:.destructive) { routine.slots.removeAll {$0.id == slot.id} } }
                        } header: { Text("\(clockText(slot.minute)) · \(dayText(slot.weekdays))") }
                    }
                    Section {
                        Button("Add shared time",systemImage:"plus") { routine.slots.append(TimeSlot(minute:720)) }.disabled(routine.slots.count >= 48)
                        Text("Each time is shared by its selected days. Turn Wednesday off for 07:00 without changing noon or 14:00.").font(.footnote).foregroundStyle(.secondary)
                    }
                } else {
                    Section("Time window") {
                        MinutePicker(title:"Start",minute:$routine.windowStart); MinutePicker(title:"End",minute:$routine.windowEnd)
                        Stepper("Every \(routine.intervalMinutes) minutes",value:$routine.intervalMinutes,in:5...720,step:5)
                        WeekdayPicker(days:$routine.windowDays)
                        Text("Overnight windows belong to the day they start. The end is included only when an interval lands on it.").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if routine.delivery == .alarm {
                    Section("Alarm sound") {
                        AlarmTonePicker(tone:Binding(get:{routine.tone ?? .system},set:{routine.tone = $0}))
                        if let tone = routine.tone, tone != .system {
                            Button("Preview tone") {
                                do { guard let url = Bundle.main.url(forResource:"alarm-\(tone.rawValue)",withExtension:"wav") else { throw LoopError.invalid("Tone unavailable in this build.") }; tonePlayer = try AVAudioPlayer(contentsOf:url); tonePlayer?.play() }
                                catch { saveError = error.localizedDescription }
                            }
                        }
                        Text("Preview uses media volume. The ringing alarm uses the system alarm volume; this app does not override it.").font(.footnote).foregroundStyle(.secondary)
                    }
                    Section("Wake-up mission") {
                        Picker("Mission",selection:$routine.mission) { Text("None").tag(MissionKind.none); Text("Arithmetic").tag(MissionKind.arithmetic); Text("Type a phrase").tag(MissionKind.typing); Text("Scan a code").tag(MissionKind.code); Text("Memory tiles").tag(MissionKind.memory); Text("Shake").tag(MissionKind.shake); Text("Walk").tag(MissionKind.walk); Text("Number order").tag(MissionKind.numberOrder); Text("Color clash").tag(MissionKind.colorWord) }
                        if [.memory,.shake,.walk,.numberOrder,.colorWord].contains(routine.mission) {
                            Picker("Difficulty",selection:Binding(get:{routine.difficulty},set:{value in routine.challengeLevel = value})) { Text("Easy").tag(1); Text("Medium").tag(2); Text("Hard").tag(3) }
                        }
                        if routine.mission == .arithmetic { Stepper("\(routine.mathCount) questions",value:$routine.mathCount,in:1...10) }
                        if routine.mission == .typing { TextField("Phrase",text:$routine.phrase) }
                        if routine.mission == .code { Button(routine.code.isEmpty ? "Scan your code" : "Replace saved code") { scanning = true }; if !routine.code.isEmpty { Label("Code saved on this phone",systemImage:"checkmark.circle") } }
                        if routine.mission != .none { Button("Preview mission") { preview = true } }
                        Text("iOS keeps its own Stop control. A mission records completion; it cannot prevent shutdown or system dismissal.").font(.footnote).foregroundStyle(.secondary)
                    }
                    Section("Snooze & wake-up check") {
                        Stepper("Snooze: \(routine.snoozeMinutes) minutes",value:$routine.snoozeMinutes,in:1...30)
                        Stepper("Snooze limit: \(routine.snoozeLimit)",value:$routine.snoozeLimit,in:0...10)
                        Stepper(routine.followUpMinutes == 0 ? "Wake-up check: off" : "Check again after \(routine.followUpMinutes) minutes",value:$routine.followUpMinutes,in:0...30)
                        Text("Open the alarm to use its mission or snooze. A wake-up check rings once after completion or a system dismissal.").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("Next seven days · local time") {
                    let result = try? ScheduleCompiler.occurrences(routine,after:Date())
                    if let result, !result.isEmpty { ForEach(Array(result.prefix(18).enumerated()),id:\.offset) { _, date in Text(date.formatted(date:.abbreviated,time:.shortened)) }; if result.count > 18 { Text("\(result.count - 18) more occurrences") } }
                    else { Text("No upcoming times. Check the schedule.").foregroundStyle(.secondary) }
                    Text("Clock times follow your current timezone. DST preview uses the next valid time and the first occurrence of a repeated time.").font(.footnote).foregroundStyle(.secondary)
                }
            }.navigationTitle("Edit routine")
                .toolbar {
                    ToolbarItem(placement:.cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement:.confirmationAction) { Button("Save") { Task { if await model.save(routine) { dismiss() } else { saveError = model.error; model.error = nil } } }.disabled(model.busy).accessibilityIdentifier("save-routine") }
                }
                .onDisappear { tonePlayer?.stop() }
                .sheet(isPresented:$scanning) { CodeScanner { routine.code = $0; scanning = false } }
                .sheet(isPresented:$preview) { MissionScreen(routine:routine,row:nil) }
                .alert("Could not save",isPresented:Binding(get:{saveError != nil},set:{shown in if !shown {saveError=nil}})) { Button("OK",role:.cancel) {} } message: { Text(saveError ?? "") }
        }
    }
}
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
                VStack(spacing:24) {
                    Image(systemName:complete ? "checkmark.circle" : "sun.max").font(.system(size:56)).foregroundStyle(.tint)
                    Text(complete ? "Done." : routine.name).font(.largeTitle.bold()).multilineTextAlignment(.center)
                    if !complete, let reason = routine.reason, !reason.isEmpty { Text(reason).font(.title2.weight(.semibold)).multilineTextAlignment(.center) }
                    if row == nil { Text("Mission preview · no alarm will ring").font(.callout).foregroundStyle(.secondary) }
                    if !complete {
                        switch routine.mission {
                        case .none: Button("I'm done") { finish() }.buttonStyle(.borderedProminent)
                        case .arithmetic:
                            Text("Question \(solved + 1) of \(routine.mathCount)").foregroundStyle(.secondary)
                            Text("\(challenge.left) + \(challenge.right)").font(.largeTitle.monospacedDigit())
                            TextField("Answer",text:$answer).keyboardType(.numberPad).textFieldStyle(.roundedBorder).accessibilityIdentifier("mission-answer")
                            Button("Check answer") { if challenge.accepts(answer) { solved += 1; answer = ""; feedback = ""; if solved >= routine.mathCount { finish() } else { challenge = ArithmeticChallenge() } } else { feedback = "Try that one again." } }.buttonStyle(.borderedProminent)
                        case .typing:
                            Text(routine.phrase).font(.title2).textSelection(.disabled)
                            TextField("Type the phrase",text:$answer,axis:.vertical).textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never)
                            Button("Check phrase") { if MissionCheck.phrase(answer,expected:routine.phrase) { finish() } else { feedback = "The phrase doesn't match yet." } }.buttonStyle(.borderedProminent)
                        case .memory: MemoryMission(level:routine.difficulty,onComplete:finish)
                        case .shake: MovementMission(mode:.shake,target:routine.difficulty * 10,onComplete:finish)
                        case .walk: MovementMission(mode:.walk,target:routine.difficulty * 20,onComplete:finish)
                        case .numberOrder: NumberOrderMission(level:routine.difficulty,onComplete:finish)
                        case .colorWord: ColorWordMission(level:routine.difficulty,onComplete:finish)
                        case .code:
                            Text("Go to your saved code and scan it.")
                            Button("Scan code",systemImage:"qrcode.viewfinder") { scanning = true }.buttonStyle(.borderedProminent)
                        }
                        if !feedback.isEmpty { Text(feedback).foregroundStyle(.secondary).accessibilityIdentifier("mission-feedback") }
                        if let row, row.snoozes < routine.snoozeLimit {
                            Button("Snooze \(routine.snoozeMinutes) minutes") { Task { if await model.finish(row,kind:.snoozed) { dismiss() } else { feedback = model.error ?? "Could not snooze."; model.error = nil } } }
                        }
                        if row != nil { Button("Emergency stop",role:.destructive) { emergency = true } }
                    } else { Button("Close") { dismiss() }.buttonStyle(.borderedProminent) }
                }.padding(28).frame(maxWidth:520).frame(maxWidth:.infinity)
            }.navigationTitle(row == nil ? "Try mission" : "Wake up")
                .toolbar { if row == nil { Button("Close") { dismiss() } } }
                .sheet(isPresented:$scanning) { CodeScanner { value in scanning = false; if MissionCheck.code(value,expected:routine.code) { finish() } else { feedback = "That is a different code." } } }
                .confirmationDialog("Stop without completing the mission?",isPresented:$emergency,titleVisibility:.visible) { Button("Stop alarm",role:.destructive) { if let row { Task { if await model.finish(row,kind:.emergencyStop) { dismiss() } } } } }
        }
    }
    func finish() { if let row { Task { if await model.finish(row,kind:.completed) { dismiss() } else { feedback = model.error ?? "Could not complete."; model.error = nil } } } else { complete = true } }
}
struct HistoryScreen: View {
    @Environment(AlarmModel.self) private var model
    @State private var search = ""
    @State private var filter = false
    @State private var date = Date()
    @State private var editing: LoopEvent?
    var events: [LoopEvent] { model.archive.events.filter { (search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) || $0.note.localizedCaseInsensitiveContains(search)) && (!filter || Calendar.current.isDate($0.date,inSameDayAs:date)) }.sorted {$0.date > $1.date} }
    var body: some View {
        List {
            Section {
                Toggle("Filter by day",isOn:$filter)
                if filter { DatePicker("Day",selection:$date,displayedComponents:.date).datePickerStyle(.graphical) }
                Text("Only recorded actions appear here. An alarm being scheduled does not prove you heard it or completed the activity.").font(.footnote).foregroundStyle(.secondary)
            }
            if events.isEmpty { ContentUnavailableView("No recorded actions",systemImage:"calendar",description:Text("Completed missions and manual check-ins will appear here.")) }
            ForEach(events) { e in
                Button { editing = e } label: { VStack(alignment:.leading,spacing:5) { Text(e.name).font(.headline); Text(eventName(e.kind)).foregroundStyle(.secondary); Text(e.date.formatted()).font(.caption); if !e.note.isEmpty { Text(e.note).font(.caption).foregroundStyle(.secondary) } } }.foregroundStyle(.primary)
                    .swipeActions { Button("Delete",role:.destructive) { Task { _ = await model.mutate { $0.events.removeAll { $0.id == e.id } } } } }
            }
        }.navigationTitle("History").searchable(text:$search,prompt:"Find a routine or note")
            .sheet(item:$editing) { EventEditor(event:$0) }
    }
}
func eventName(_ kind: EventKind) -> String { switch kind { case .completed: "Mission completed"; case .dismissed:"System dismissal"; case .snoozed:"Snoozed"; case .emergencyStop:"Emergency stop"; case .manual:"Manual check-in"; case .followUpScheduled:"Wake-up check scheduled" } }
struct EventEditor: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var event: LoopEvent
    var body: some View { NavigationStack { Form { Text(event.name).font(.headline); Text(eventName(event.kind)); DatePicker("Recorded time",selection:$event.date,in:...Date()); TextField("Note",text:$event.note,axis:.vertical); Text("Corrections are marked as manually recorded.").font(.footnote) }.navigationTitle("Edit record").toolbar { Button("Save") { var copy = event; copy.kind = .manual; let saved = copy; Task { if await model.mutate({ a in if let i = a.events.firstIndex(where:{ item in item.id == saved.id }) { a.events[i] = saved } }) { dismiss() } } }; Button("Cancel") { dismiss() } } } }
}
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json,.commaSeparatedText] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents:data) }
}
struct SettingsScreen: View {
    @Environment(AlarmModel.self) private var model
    @State private var before = false
    @State private var importing = false
    @State private var exporting = false
    @State private var document = BackupDocument(data:Data())
    @State private var exportType = UTType.json
    @State private var exportName = "alarm-loops-backup"
    @State private var pendingImport: LoopArchive?
    @State private var confirmImport = false
    var body: some View {
        Form {
            Section("Alarm delivery") {
                Text(model.driver.status)
                Button("Request permissions") { Task { await model.authorize() } }
                Link("Open system settings",destination:URL(string:UIApplication.openSettingsURLString)!)
                Button("Ring a test alarm in 10 seconds") { Task { await model.testAlarm() } }
                Button("Retry schedule registration") { Task { await model.synchronize() } }
                Text(model.scheduling).font(.footnote)
                Text("Uses your selected tone and system alarm volume. Test on your phone before relying on an alarm. iOS provides its own Stop control.").font(.footnote).foregroundStyle(.secondary)
            }
            Section("Less setup") {
                NavigationLink("New alarm defaults") { AlarmDefaultsEditor(defaults:model.archive.alarmDefaults ?? AlarmDefaults()) }
            }
            Section("Your data") {
                Button("Export JSON backup") { do { document = BackupDocument(data:try BackupCodec.encode(model.archive)); exportType = .json; exportName = "alarm-loops-backup"; exporting = true } catch { model.error = error.localizedDescription } }
                Button("Export history CSV") { document = BackupDocument(data:Data(BackupCodec.csv(model.archive).utf8)); exportType = .commaSeparatedText; exportName = "alarm-loops-history"; exporting = true }
                Button("Import backup") { importing = true }
                Text("Local storage. No accounts, ads or analytics. Your device backup may include these records. Imports add new routines disabled; they never arm alarms automatically.").font(.footnote).foregroundStyle(.secondary)
            }
            Section("Troubleshooting") {
                Button("Export diagnostics") { Task { do { document = BackupDocument(data:try await model.diagnosticData()); exportType = .json; exportName = "alarm-loops-diagnostics"; exporting = true } catch { model.error = error.localizedDescription } } }
                Text("A local report with app and OS versions, alarm permission, registration counts and error flags. Excludes names, schedules, scanned codes, phrases and history. Nothing is sent automatically. Registration counts do not prove an alarm rang.").font(.footnote).foregroundStyle(.secondary)
            }
            AboutSection(onShowBeforeYouBuy:{before = true},notStartedStatus:"Free trial not started. Existing alarms and history remain available.")
            Section("About") { AboutVersionRow(); AboutLicenseRow(repositoryURL:URL(string:"https://github.com/drei-gg/sevens")!); AboutNoticesRow(notices:["Apple frameworks and SevenKit. No third-party libraries."]); Text("Only Apple handles purchase and restore requests. Scanned mission codes stay on this phone and are never opened as links.").font(.footnote) }
        }.navigationTitle("Settings")
            .sheet(isPresented:$before) { BeforeYouBuyView(content:AlarmGate.before) { _ in before = false } }
            .fileExporter(isPresented:$exporting,document:document,contentType:exportType,defaultFilename:exportName) { if case let .failure(error) = $0 { model.error = error.localizedDescription } }
            .fileImporter(isPresented:$importing,allowedContentTypes:[.json]) { result in
                do { let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }; let size = try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0; guard size <= BackupCodec.maxBytes else { throw LoopError.invalid("Backup exceeds 32 MB.") }; pendingImport = try BackupCodec.decode(Data(contentsOf:url)); confirmImport = true } catch { model.error = error.localizedDescription }
            }
            .confirmationDialog("Import \(pendingImport?.routines.count ?? 0) routines and \(pendingImport?.events.count ?? 0) history records? Existing routine IDs are kept; new routines start disabled.",isPresented:$confirmImport,titleVisibility:.visible) {
                Button("Import disabled routines") { if let incoming = pendingImport { Task { _ = await model.mutate { $0 = try BackupCodec.merge(incoming,into:$0) }; pendingImport = nil } } }
            }
    }
}
