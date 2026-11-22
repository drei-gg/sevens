import Foundation
import SwiftUI
import Observation
import SevenKit
import SevenSchedule
import AlarmCore
import AlarmKit
import AppIntents
import UserNotifications
import WidgetKit

@MainActor public protocol AlarmDriver {
    func request() async throws
    func identifiers() async throws -> Set<UUID>
    func widgetIdentifiers() async throws -> Set<UUID>
    func schedule(_ registration: Registration) async throws
    func cancel(_ registration: Registration) async throws
    func stop(_ registration: Registration) async throws
    var status: String { get }
}
public extension AlarmDriver {
    func widgetIdentifiers() async throws -> Set<UUID> { try await identifiers() }
}
@MainActor public final class NativeAlarmDriver: AlarmDriver {
    /// Quiet reminders, on SevenKit's notification scheduler. Their identifiers are the
    /// registrations' bare UUIDs, as they always were, so pending reminders survive the upgrade.
    private let reminders = NotificationScheduler(owns: { UUID(uuidString: $0) != nil })
    public init() {}
    public var status: String { switch AlarmManager.shared.authorizationState { case .authorized: f("Alarms allowed"); case .denied: f("Alarms blocked in Settings"); case .notDetermined: f("Alarm permission not requested"); @unknown default: f("Alarm permission unknown") } }
    public func request() async throws {
        _ = try await AlarmManager.shared.requestAuthorization()
        _ = try await reminders.requestAuthorization([.alert,.sound,.badge])
    }
    public func identifiers() async throws -> Set<UUID> {
        let alarms = try AlarmManager.shared.alarms
        let pending = await reminders.pendingIDs()
        return Set(alarms.map(\.id) + pending.compactMap { UUID(uuidString:$0) })
    }
    public func widgetIdentifiers() async throws -> Set<UUID> {
        let alarms = AlarmManager.shared.authorizationState == .authorized ? try AlarmManager.shared.alarms.map(\.id) : []
        let notifications = await reminders.authorized() ? await reminders.pendingIDs().compactMap { UUID(uuidString: $0) } : []
        return Set(alarms + notifications)
    }
    public func schedule(_ r: Registration) async throws {
        if r.routine.delivery == .reminder {
            guard await reminders.authorized() else { throw LoopFailure.invalid(f("Allow notifications in Settings to deliver quiet reminders.")) }
            // Each weekday gets its own native notification (the core splits weekly reminders).
            var c = DateComponents()
            if let date = r.date { c = Calendar.current.dateComponents([.year,.month,.day,.hour,.minute,.second],from:date) }
            else { let minute = Int(r.minute ?? 0); c.hour = minute/60; c.minute = minute%60; c.weekday = r.weekdays.first.map(Int.init) }
            try await reminders.add(ScheduledNotification(id: r.id.uuidString, title: r.routine.name, body: f("Time for your routine."), trigger: .wallClock(c, repeats: r.date == nil))); return
        }
        guard AlarmManager.shared.authorizationState == .authorized else { throw LoopFailure.invalid(f("Allow alarms in Settings before relying on this schedule.")) }
        let schedule: Alarm.Schedule
        if let date = r.date { schedule = .fixed(date) }
        else {
            let weekdays: [Locale.Weekday] = [.sunday,.monday,.tuesday,.wednesday,.thursday,.friday,.saturday]
            let minute = Int(r.minute ?? 0)
            schedule = .relative(.init(time:.init(hour:minute/60,minute:minute%60),repeats:.weekly(r.weekdays.map { weekdays[Int($0)-1] })))
        }
        let alert = AlarmPresentation.Alert(title:LocalizedStringResource(stringLiteral:r.routine.name),secondaryButton:AlarmButton(text:"Open",textColor:.white,systemImageName:"arrow.up.right"),secondaryButtonBehavior:.custom)
        let attributes = AlarmAttributes<LoopMetadata>(presentation:AlarmPresentation(alert:alert),metadata:LoopMetadata(),tintColor:.accentColor)
        let configuration = AlarmManager.AlarmConfiguration(schedule:schedule,attributes:attributes,stopIntent:StopLoopIntent(id:r.id.uuidString),secondaryIntent:OpenLoopIntent(id:r.id.uuidString),sound:ImportedSounds.live.soundName(r.routine).map { .named($0) } ?? .default)
        do { _ = try await AlarmManager.shared.schedule(id:r.id,configuration:configuration) }
        catch {
            // An edit keeps its ID. If AlarmKit refuses an ID it already holds, replace that alarm once;
            // any other failure leaves the existing registration untouched.
            guard (try? AlarmManager.shared.alarms.contains(where: { $0.id == r.id })) == true else { throw error }
            try AlarmManager.shared.cancel(id:r.id)
            _ = try await AlarmManager.shared.schedule(id:r.id,configuration:configuration)
        }
    }
    public func cancel(_ r: Registration) async throws {
        if r.routine.delivery == .reminder { await reminders.remove([r.id.uuidString]) }
        else { try AlarmManager.shared.cancel(id:r.id) }
    }
    public func stop(_ r: Registration) async throws {
        if r.routine.delivery == .alarm {
            let current = try AlarmManager.shared.alarms
            if current.contains(where: { $0.id == r.id && $0.state == .alerting }) { try AlarmManager.shared.stop(id:r.id) }
        }
    }
}
struct LoopMetadata: AlarmMetadata {}
public struct OpenLoopIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Open alarm mission"
    public static let openAppWhenRun: Bool = true
    @Parameter(title:"Alarm ID") public var alarmID: String
    public init() {}
    public init(id: String) { alarmID = id }
    public func perform() async throws -> some IntentResult { try await AlarmModel.shared.openNative(alarmID); return .result() }
}
public struct StopLoopIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Record alarm dismissal"
    public static let openAppWhenRun: Bool = false
    @Parameter(title:"Alarm ID") public var alarmID: String
    public init() {}
    public init(id: String) { alarmID = id }
    public func perform() async throws -> some IntentResult { try await AlarmModel.shared.dismissNative(alarmID); return .result() }
}
@MainActor public final class PreviewAlarmDriver: AlarmDriver {
    public var rows: [UUID:Registration] = [:]
    public var failure: String?
    /// Tests set this to fail like a native cancel of an alarm that no longer exists.
    public var strictCancel = false
    public init() {}
    public var status: String { f("Preview mode: no alarms are scheduled") }
    public func request() async throws {}
    public func identifiers() async throws -> Set<UUID> { Set(rows.keys) }
    public func schedule(_ r: Registration) async throws { if let failure { throw LoopFailure.invalid(failure) }; rows[r.id] = r }
    public func cancel(_ r: Registration) async throws {
        if strictCancel && rows[r.id] == nil { throw LoopFailure.invalid(f("No such alarm.")) }
        rows.removeValue(forKey:r.id)
    }
    public func stop(_ r: Registration) async throws {}
}
@Observable @MainActor public final class AlarmModel {
    public static let shared = AlarmModel.live()
    public let store: Store
    public private(set) var archive = LoopArchive()
    public private(set) var ready = false
    public private(set) var busy = false
    public var error: String?
    public var loadError: String?
    public var widgetError: String?
    public private(set) var widgetSnapshot: AlarmWidgetSnapshot?
    @ObservationIgnored private var widgetReload: Task<Void, Never>?
    public var scheduling = f("Checking alarm registrations…")
    public var previewMission: Routine?
    public var mission: Registration?
    public var isPreview = false
    @ObservationIgnored public let repository: LoopRepository
    @ObservationIgnored public let driver: any AlarmDriver
    @ObservationIgnored private var started = false
    @ObservationIgnored private var syncing = false
    @ObservationIgnored private var syncAgain = false
    public init(store: Store, repository: LoopRepository, driver: any AlarmDriver) { self.store = store; self.repository = repository; self.driver = driver }
    private static func live() -> AlarmModel {
        let config = SevenConfig(bundle:.main) ?? SevenConfig(trialProductID:"alarm.trial7",unlockProductID:"alarm.unlock")
        let base = FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("AlarmLoops")
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-alarm.ui") || args.contains("-alarm.demo") {
            let defaults = UserDefaults(suiteName:"AlarmLoops.UITests")!
            if args.contains("-alarm.reset") { defaults.removePersistentDomain(forName:"AlarmLoops.UITests") }
            let source = LocalSource(defaults:defaults); source.apply(args.contains("-alarm.expired") ? .expired : .unlocked)
            let store = Store(config:config,source:source,defaults:defaults); store.markBeforeYouBuyShown(); store.markTrialEndedShown()
            let file = base.appendingPathComponent("uitest.json")
            if args.contains("-alarm.reset") { try? FileManager.default.removeItem(at:file) }
            var seed = LoopArchive()
            if args.contains("-alarm.demo") {
                var wake = Routine(); wake.mission = .arithmetic
                if let choice = args.first(where: { $0.hasPrefix("-alarm.mission.") }), let kind = MissionKind(rawValue:String(choice.dropFirst("-alarm.mission.".count))) { wake.mission = kind }
                var loop = Routine(kind:.loop); loop.name = "Workday check-in"; loop.slots = [TimeSlot(minute:420,weekdays:[2,6]),TimeSlot(minute:720,weekdays:[2,4,6]),TimeSlot(minute:840,weekdays:[2,4,6])]
                seed.routines = [wake,loop]; seed.events = [LoopEvent(routine:wake,kind:.completed,date:Date().addingTimeInterval(-3600)),LoopEvent(routine:loop,kind:.manual,date:Date().addingTimeInterval(-1800))]
                // The polish tour's fuller week: more alarms (one off, one in a group, one with a reason)
                // and a week of records so History's tiles and chart have something to show.
                var weekend = Routine(); weekend.name = "Weekend"; weekend.slots = [TimeSlot(minute:540,weekdays:[1,7])]; weekend.reason = "Farmers market at 9:30"
                var gym = Routine(); gym.name = "Gym"; gym.group = "Training"; gym.enabled = false; gym.mission = .walk; gym.slots = [TimeSlot(minute:375,weekdays:[3,5])]
                seed.routines += [weekend,gym]
                let day = 86_400.0
                for back in 1...6 {
                    let at = Date().addingTimeInterval(-Double(back) * day)
                    seed.events.append(LoopEvent(routine:wake,kind:back % 3 == 0 ? .snoozed : .completed,date:at))
                    if back % 2 == 0 { seed.events.append(LoopEvent(routine:loop,kind:.manual,date:at.addingTimeInterval(5 * 3600),note:back == 2 ? "Stretched and refilled water" : "")) }
                }
            }
            if args.contains("-alarm.sound") {
                if let source = Bundle.main.url(forResource: "alarm-chime", withExtension: "wav"), let sound = try? ImportedSounds.live.load(source) {
                    var routine = Routine(); routine.name = "Imported sound"; routine.enabled = false; routine.tone = .pulse; routine.importedSound = sound
                    seed.routines = [routine]
                    if args.contains("-alarm.sound-missing") { try? FileManager.default.removeItem(at: ImportedSounds.live.file(sound)) }
                }
            }
            let model = AlarmModel(store:store,repository:LoopRepository(file:(args.contains("-alarm.demo") || args.contains("-alarm.sound")) ? nil : file,seed:seed),driver:PreviewAlarmDriver()); model.isPreview = true; return model
        }
        #endif
        return AlarmModel(store:Store(config:config),repository:LoopRepository(file:base.appendingPathComponent("archive.v1.json")),driver:NativeAlarmDriver())
    }
    private func publishWidget(verifiedIDs: Set<UUID> = []) {
        do {
            let snapshot = loadError == nil ? try alarmWidgetSnapshot(archive: archive, verifiedIds: Array(verifiedIDs), now: Date(), calendar: CalendarSpec(.current)) : nil
            widgetSnapshot = snapshot
            // Unit-test models keep their projection in memory; only the live/UI-test singleton
            // writes the shared app group. Preview rows remain explicitly simulated in the app.
            if self === AlarmModel.shared {
                guard let url = AlarmWidgetStore.url(group: Bundle.main.object(forInfoDictionaryKey: "SevensAppGroup") as? String) else {
                    throw LoopFailure.invalid(f("The widget shared container is unavailable."))
                }
                try AlarmWidgetStore.write(snapshot, to: url)
                widgetReload?.cancel()
                widgetReload = Task {
                    do { try await Task.sleep(for: .milliseconds(300)) } catch { return }
                    WidgetCenter.shared.reloadTimelines(ofKind: "alarm.schedule")
                }
            }
            widgetError = nil
        } catch {
            widgetSnapshot = nil
            if self === AlarmModel.shared, let url = AlarmWidgetStore.url(group: Bundle.main.object(forInfoDictionaryKey: "SevensAppGroup") as? String) {
                try? AlarmWidgetStore.write(nil, to: url)
                WidgetCenter.shared.reloadTimelines(ofKind: "alarm.schedule")
            }
            widgetError = f("The Home Screen widget could not refresh. Open the app for the latest schedule.")
        }
    }
    private func publishVerifiedWidget(excluding: Set<UUID> = []) async {
        do { publishWidget(verifiedIDs: try await driver.widgetIdentifiers().subtracting(excluding)) }
        catch { publishWidget() }
    }
    public func start() async {
        guard !started else { return }; started = true
        await reload(); await store.start(); await synchronize()
    }
    public func resume() async { await reload(); await store.refresh(); await synchronize() }
    public func reload() async {
        do { archive = try await repository.read(); ready = true; loadError = nil; if let pending = archive.pending { mission = pending } }
        catch { loadError = error.localizedDescription }
        publishWidget()
    }
    @discardableResult public func mutate(_ change: @Sendable (inout LoopArchive) throws -> Void) async -> Bool {
        do {
            let before = archive
            archive = try await repository.change(change)
            if before.routines != archive.routines || (!syncing && before.installed != archive.installed) { publishWidget() }
            return true
        }
        catch { self.error = error.localizedDescription; return false }
    }
    public func save(_ r: Routine) async -> Bool {
        guard store.paidFeaturesAvailable else { error = f("Start the trial or unlock in Settings to create or edit schedules. Existing enabled alarms keep running."); return false }
        guard !busy else { return false }; busy = true; defer { busy = false }
        do { try LoopFailure.sentence { try routineCheckSave(routine: r, now: Date()) } }
        catch { self.error = error.localizedDescription; return false }
        guard await mutate({ a in a.routines.removeAll { $0.id == r.id }; a.routines.append(r) }) else { return false }
        await synchronize(); return true
    }
    public func toggle(_ r: Routine, enabled: Bool) async {
        guard !enabled || store.paidFeaturesAvailable else { error = f("Unlock to re-enable this routine. Existing enabled alarms keep running."); return }
        _ = await mutate { a in if let i = a.routines.firstIndex(where:{$0.id == r.id}) { a.routines[i].enabled = enabled } }; await synchronize()
    }
    public func duplicate(_ r: Routine) async {
        _ = await save(duplicateRoutine(routine: r))
    }
    public func newRoutine(kind: RoutineKind = .alarm) -> Routine {
        (archive.alarmDefaults ?? AlarmDefaults()).routine(kind:kind)
    }
    public func saveDefaults(_ defaults: AlarmDefaults) async -> Bool {
        await mutate { $0.alarmDefaults = defaults }
    }
    @discardableResult public func nap(minutes: Int) async -> Bool {
        do { return await save(try LoopFailure.sentence { try napRoutine(defaults: archive.alarmDefaults, minutes: Int32(clamping: minutes), now: Date()) }) }
        catch { self.error = error.localizedDescription; return false }
    }
    public func toggleGroup(_ name: String, enabled: Bool) async {
        guard !enabled || store.paidFeaturesAvailable else { error = f("Unlock to re-enable routines."); return }
        _ = await mutate { a in for i in a.routines.indices where a.routines[i].group?.trimmingCharacters(in:.whitespacesAndNewlines) == name { a.routines[i].enabled = enabled } }
        await synchronize()
    }
    public func clearWakeUpChecks() async {
        do {
            let liveIDs = try await driver.identifiers()
            for row in archive.installed where row.purpose == "followUp" {
                // A check that already rang has no native alarm left to cancel; drop its receipt anyway.
                if liveIDs.contains(row.id) { try await driver.cancel(row) }
                guard await mutate({ $0.installed.removeAll { $0.id == row.id } }) else { return }
            }
            await synchronize()
        } catch { self.error = error.localizedDescription }
    }
    public func delete(_ r: Routine) async { _ = await mutate { $0.routines.removeAll { $0.id == r.id } }; await synchronize() }
    /// Every routine's registrations, weekly quiet reminders split per weekday, refusing more than
    /// 60 reminders rather than dropping some (the core's `desired_registrations`).
    public func desired() throws -> [Registration] {
        try LoopFailure.sentence { try desiredRegistrations(archive: archive, now: Date()) }
    }
    public func synchronize() async {
        guard ready else { return }
        if syncing { syncAgain = true; return }; syncing = true
        defer { syncing = false }
        repeat {
            syncAgain = false
            do {
                // A one-off that has rung is finished: show it as off rather than enabled with a past date.
                let now = Date()
                if routinesFinishPastOnce(routines: archive.routines, now: now) != archive.routines {
                    _ = await mutate { a in a.routines = routinesFinishPastOnce(routines: a.routines, now: now) }
                }
                let desired = try desired()
                let liveIDs = try await driver.identifiers()
                var failures: [String] = []
                var unverified: Set<UUID> = []
                for row in desired {
                    let old = archive.installed.first { $0.id == row.id }
                    if old == row && liveIDs.contains(row.id) { continue }
                    do {
                        // Persist the ID before installation so even a later write failure is recoverable.
                        if old == nil { guard await mutate({ $0.installed.append(row) }) else { throw LoopFailure.invalid(f("Could not save alarm receipt.")) } }
                        try await driver.schedule(row)
                        guard await mutate({ a in a.installed.removeAll { $0.id == row.id }; a.installed.append(row) }) else { throw LoopFailure.invalid(f("Alarm scheduled, but its receipt could not be updated. Retry before relying on it.")) }
                    } catch { unverified.insert(row.id); failures.append("\(row.routine.name): \(error.localizedDescription)") }
                }
                let wanted = Set(desired.map(\.id))
                for row in archive.installed where !wanted.contains(row.id) && failures.isEmpty {
                    // Keep a live extra of an enabled routine, the open mission's row, and anything that
                    // rang within the hour, so the Open and Stop intents that may run right after this
                    // launch's reconcile can still find it (the core's `receipt_keep`).
                    if receiptKeep(row: row, archive: archive, live: liveIDs.contains(row.id), now: Date()) { continue }
                    do {
                        if liveIDs.contains(row.id) { try await driver.cancel(row) }
                        _ = await mutate { $0.installed.removeAll { $0.id == row.id } }
                    } catch { failures.append("Could not remove \(row.routine.name): \(error.localizedDescription)") }
                }
                await publishVerifiedWidget(excluding: unverified)
                scheduling = failures.isEmpty ? (isPreview ? driver.status : "\(Self.timesRegistered(desired.count)) \(driver.status).") : failures.joined(separator:"\n")
            } catch { scheduling = error.localizedDescription; publishWidget() }
        } while syncAgain
    }
    public func authorize() async {
        do { try await driver.request(); await synchronize() } catch { self.error = error.localizedDescription }
    }
    public func openNative(_ id: String) async throws {
        await reload()
        guard var row = archive.installed.first(where:{$0.id.uuidString == id}) else { throw LoopFailure.invalid(f("The alarm is no longer in your saved schedule.")) }
        if archive.pending?.id == row.id, let pending = archive.pending { row = pending }
        // Each ringing is its own occurrence, so snoozes and checks from different days never share IDs.
        if row.occurrenceID == nil { row.occurrenceID = UUID() }
        let opened = row
        guard await mutate({ $0.pending = opened }) else { throw LoopFailure.invalid(f("Could not save the active mission.")) }
        mission = opened
    }
    public func dismissNative(_ id: String) async throws {
        await reload()
        guard var row = archive.installed.first(where:{$0.id.uuidString == id}) else { return }
        if archive.pending?.id == row.id, let pending = archive.pending { row = pending }
        if row.purpose == Self.testPurpose {
            // The Settings test is not a routine: no record, no wake-up check.
            let rowID = row.id
            guard await mutate({ if $0.pending?.id == rowID { $0.pending = nil } }) else { throw LoopFailure.invalid(f("Could not stop the test alarm.")) }
            if mission?.id == rowID { mission = nil }
            return
        }
        if row.occurrenceID == nil { row.occurrenceID = UUID() }
        let event = LoopEvent(routine:row.routine,kind:.dismissed,note:"Stopped with the system control; mission completion is unconfirmed.")
        let rowID = row.id
        guard await mutate({ $0.events.append(event); if $0.pending?.id == rowID { $0.pending = nil } }) else { throw LoopFailure.invalid(f("Could not record dismissal.")) }
        if row.purpose != "followUp" && row.routine.followUpMinutes > 0 { try await extra(row,minutes:Int(row.routine.followUpMinutes),purpose:"followUp") }
    }
    public func extra(_ row: Registration, minutes: Int, purpose: String) async throws {
        let extra = followOnRegistration(row: row, minutes: Int32(clamping: minutes), purpose: purpose, now: Date())
        let saved = extra
        guard await mutate({ a in a.installed.removeAll { $0.id == saved.id }; a.installed.append(saved) }) else { throw LoopFailure.invalid(f("Could not save follow-up alarm.")) }
        do { try await driver.schedule(extra); await publishVerifiedWidget() } catch { _ = await mutate { $0.installed.removeAll { $0.id == saved.id } }; throw error }
    }
    public func finish(_ row: Registration, kind: EventKind) async -> Bool {
        guard !busy else { return false }; busy = true; defer { busy = false }
        do {
            // The Settings test alarm never writes History or schedules a follow-up.
            let record = row.purpose != Self.testPurpose
            if kind == .snoozed {
                guard row.snoozes < row.routine.snoozeLimit else { throw LoopFailure.invalid(f("Snooze limit reached.")) }
                try await extra(row,minutes:Int(row.routine.snoozeMinutes),purpose:"snooze")
            } else if record && kind == .completed && row.purpose != "followUp" && row.routine.followUpMinutes > 0 { try await extra(row,minutes:Int(row.routine.followUpMinutes),purpose:"followUp") }
            if kind == .emergencyStop || (kind == .completed && row.purpose == "followUp") {
                let liveIDs = try await driver.identifiers()
                for extra in archive.installed where extra.routine.id == row.routine.id && extra.purpose != "schedule" && extra.id != row.id {
                    if liveIDs.contains(extra.id) { try await driver.cancel(extra) }
                    _ = await mutate { $0.installed.removeAll { $0.id == extra.id } }
                }
            }
            try await driver.stop(row)
            let event = LoopEvent(routine:row.routine,kind:kind)
            guard await mutate({ a in if record { a.events.append(event) }; a.pending = nil }) else { return false }
            mission = nil; await publishVerifiedWidget(); return true
        } catch { self.error = error.localizedDescription; return false }
    }
    public func manual(_ r: Routine, date: Date) async { let e = LoopEvent(routine:r,kind:.manual,date:date); _ = await mutate { $0.events.append(e) } }
    /// "1 alarm time registered." or "5 alarm times registered." (a String Catalog plural).
    nonisolated static func timesRegistered(_ count: Int) -> String { String(localized: "\(count) alarm times registered.", bundle: .module) }
    /// The registration purpose of Settings' test alarm (the core keeps its receipt).
    public static let testPurpose = "test"
    /// Settings' ten-second test: the selected default tone, and nothing else from the defaults. It has
    /// no snooze or wake-up check, and Stop or Open never writes History.
    public func testAlarm() async {
        do {
            var r = newRoutine(); r.name = f("Test alarm"); r.timing = .once; r.once = Date().addingTimeInterval(10); r.snoozeLimit = 0; r.followUpMinutes = 0
            let row = Registration(id:UUID(),routine:r,date:r.once,purpose:Self.testPurpose)
            guard await mutate({ $0.installed.append(row) }) else { return }; try await driver.schedule(row); scheduling = f("Test alarm scheduled for 10 seconds from now.")
        } catch { self.error = error.localizedDescription }
    }
}

public struct LoopIntentsPackage: AppIntentsPackage {}
