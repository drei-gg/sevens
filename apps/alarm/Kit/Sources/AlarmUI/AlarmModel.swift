import Foundation
import SwiftUI
import Observation
import SevenKit
import AlarmCore
import AlarmKit
import AppIntents
import UserNotifications

@MainActor public protocol AlarmDriver {
    func request() async throws
    func identifiers() async throws -> Set<UUID>
    func schedule(_ registration: Registration) async throws
    func cancel(_ registration: Registration) async throws
    func stop(_ registration: Registration) async throws
    var status: String { get }
}
@MainActor public final class NativeAlarmDriver: AlarmDriver {
    public init() {}
    public var status: String { switch AlarmManager.shared.authorizationState { case .authorized: "Alarms allowed"; case .denied: "Alarms blocked in Settings"; case .notDetermined: "Alarm permission not requested"; @unknown default: "Alarm permission unknown" } }
    public func request() async throws {
        _ = try await AlarmManager.shared.requestAuthorization()
        _ = try await UNUserNotificationCenter.current().requestAuthorization(options:[.alert,.sound,.badge])
    }
    public func identifiers() async throws -> Set<UUID> {
        let alarms = try AlarmManager.shared.alarms
        let reminders = await UNUserNotificationCenter.current().pendingNotificationRequests()
        return Set(alarms.map(\.id) + reminders.compactMap { UUID(uuidString:$0.identifier) })
    }
    public func schedule(_ r: Registration) async throws {
        if r.routine.delivery == .reminder {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { throw LoopError.invalid("Allow notifications in Settings to deliver quiet reminders.") }
            let content = UNMutableNotificationContent(); content.title = r.routine.name; content.body = "Time for your routine."; content.sound = .default
            // Each weekday gets its own native notification. AlarmDriver receipt ID is the root.
            // A weekly registration is split upstream for notification delivery.
            var c = DateComponents()
            if let date = r.date { c = Calendar.current.dateComponents([.year,.month,.day,.hour,.minute,.second],from:date) }
            else { c.hour = (r.minute ?? 0)/60; c.minute = (r.minute ?? 0)%60; c.weekday = r.weekdays.first }
            let trigger = UNCalendarNotificationTrigger(dateMatching:c,repeats:r.date == nil)
            try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier:r.id.uuidString,content:content,trigger:trigger)); return
        }
        guard AlarmManager.shared.authorizationState == .authorized else { throw LoopError.invalid("Allow alarms in Settings before relying on this schedule.") }
        let schedule: Alarm.Schedule
        if let date = r.date { schedule = .fixed(date) }
        else {
            let weekdays: [Locale.Weekday] = [.sunday,.monday,.tuesday,.wednesday,.thursday,.friday,.saturday]
            schedule = .relative(.init(time:.init(hour:(r.minute ?? 0)/60,minute:(r.minute ?? 0)%60),repeats:.weekly(r.weekdays.map { weekdays[$0-1] })))
        }
        let alert = AlarmPresentation.Alert(title:LocalizedStringResource(stringLiteral:r.routine.name),secondaryButton:AlarmButton(text:"Open",textColor:.white,systemImageName:"arrow.up.right"),secondaryButtonBehavior:.custom)
        let attributes = AlarmAttributes<LoopMetadata>(presentation:AlarmPresentation(alert:alert),metadata:LoopMetadata(),tintColor:.accentColor)
        let configuration = AlarmManager.AlarmConfiguration(schedule:schedule,attributes:attributes,stopIntent:StopLoopIntent(id:r.id.uuidString),secondaryIntent:OpenLoopIntent(id:r.id.uuidString),sound:(r.routine.tone ?? .system) == .system ? .default : .named("alarm-\(r.routine.tone!.rawValue).wav"))
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
        if r.routine.delivery == .reminder { UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers:[r.id.uuidString]) }
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
    public var status: String { "Preview mode — no alarms scheduled" }
    public func request() async throws {}
    public func identifiers() async throws -> Set<UUID> { Set(rows.keys) }
    public func schedule(_ r: Registration) async throws { if let failure { throw LoopError.invalid(failure) }; rows[r.id] = r }
    public func cancel(_ r: Registration) async throws {
        if strictCancel && rows[r.id] == nil { throw LoopError.invalid("No such alarm.") }
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
    public var scheduling = "Checking alarm registrations…"
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
            }
            let model = AlarmModel(store:store,repository:LoopRepository(file:args.contains("-alarm.demo") ? nil : file,seed:seed),driver:PreviewAlarmDriver()); model.isPreview = true; return model
        }
        #endif
        return AlarmModel(store:Store(config:config),repository:LoopRepository(file:base.appendingPathComponent("archive.v1.json")),driver:NativeAlarmDriver())
    }
    public func start() async {
        guard !started else { return }; started = true
        await reload(); await store.start(); await synchronize()
    }
    public func resume() async { await reload(); await store.refresh(); await synchronize() }
    public func reload() async {
        do { archive = try await repository.read(); ready = true; loadError = nil; if let pending = archive.pending { mission = pending } }
        catch { loadError = error.localizedDescription }
    }
    @discardableResult public func mutate(_ change: @Sendable (inout LoopArchive) throws -> Void) async -> Bool {
        do { archive = try await repository.change(change); return true }
        catch { self.error = error.localizedDescription; return false }
    }
    public func save(_ r: Routine) async -> Bool {
        guard store.paidFeaturesAvailable else { error = "Start the trial or unlock in Settings to create or edit schedules. Existing enabled alarms keep running."; return false }
        guard !busy else { return false }; busy = true; defer { busy = false }
        do { try r.validate(); _ = try ScheduleCompiler.registrations(r); if r.timing == .once && r.enabled && r.once <= Date() { throw LoopError.invalid("Choose a future date for this alarm.") } }
        catch { self.error = error.localizedDescription; return false }
        guard await mutate({ a in a.routines.removeAll { $0.id == r.id }; a.routines.append(r) }) else { return false }
        await synchronize(); return true
    }
    public func toggle(_ r: Routine, enabled: Bool) async {
        guard !enabled || store.paidFeaturesAvailable else { error = "Unlock to re-enable this routine. Existing enabled alarms keep running."; return }
        _ = await mutate { a in if let i = a.routines.firstIndex(where:{$0.id == r.id}) { a.routines[i].enabled = enabled } }; await synchronize()
    }
    public func duplicate(_ r: Routine) async {
        var copy = r; copy.id = UUID(); copy.name = String((r.name + " copy").prefix(100)); copy.enabled = false
        _ = await save(copy)
    }
    public func newRoutine(kind: RoutineKind = .alarm) -> Routine {
        (archive.alarmDefaults ?? AlarmDefaults()).routine(kind:kind)
    }
    public func saveDefaults(_ defaults: AlarmDefaults) async -> Bool {
        await mutate { $0.alarmDefaults = defaults }
    }
    @discardableResult public func nap(minutes: Int) async -> Bool {
        guard (1...720).contains(minutes) else { error = "Choose a nap between 1 and 720 minutes."; return false }
        var r = newRoutine(); r.name = "\(minutes)-minute nap"; r.timing = .once; r.once = Date().addingTimeInterval(Double(minutes) * 60)
        return await save(r)
    }
    public func toggleGroup(_ name: String, enabled: Bool) async {
        guard !enabled || store.paidFeaturesAvailable else { error = "Unlock to re-enable routines."; return }
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
    public func desired() throws -> [Registration] {
        var rows: [Registration] = []
        for r in archive.routines {
            for row in try ScheduleCompiler.registrations(r) {
                if r.delivery == .reminder && row.date == nil {
                    for day in row.weekdays { var copy = row; copy.id = ScheduleCompiler.identifier(row.id,key:"weekday:\(day)"); copy.weekdays = [day]; rows.append(copy) }
                } else { rows.append(row) }
            }
        }
        // Notification APIs have a bounded queue. Refuse truncation before touching it.
        guard rows.filter({$0.routine.delivery == .reminder}).count <= 60 else { throw LoopError.invalid("These routines need more than 60 quiet reminders. Reduce their times or weekdays; no reminders were silently dropped.") }
        return rows
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
                if archive.routines.contains(where: { $0.timing == .once && $0.enabled && $0.once <= now }) {
                    _ = await mutate { a in for i in a.routines.indices where a.routines[i].timing == .once && a.routines[i].enabled && a.routines[i].once <= now { a.routines[i].enabled = false } }
                }
                let desired = try desired()
                let liveIDs = try await driver.identifiers()
                var failures: [String] = []
                for row in desired {
                    let old = archive.installed.first { $0.id == row.id }
                    if old == row && liveIDs.contains(row.id) { continue }
                    do {
                        // Persist the ID before installation so even a later write failure is recoverable.
                        if old == nil { guard await mutate({ $0.installed.append(row) }) else { throw LoopError.invalid("Could not save alarm receipt.") } }
                        try await driver.schedule(row)
                        guard await mutate({ a in a.installed.removeAll { $0.id == row.id }; a.installed.append(row) }) else { throw LoopError.invalid("Alarm scheduled, but its receipt could not be updated. Retry before relying on it.") }
                    } catch { failures.append("\(row.routine.name): \(error.localizedDescription)") }
                }
                let wanted = Set(desired.map(\.id))
                for row in archive.installed where !wanted.contains(row.id) && failures.isEmpty {
                    let enabled = archive.routines.contains(where:{$0.id == row.routine.id && $0.enabled}) || row.purpose == "test"
                    let activeExtra = enabled && ((row.purpose != "schedule" && (row.date ?? .distantPast) > Date()) || (row.date != nil && liveIDs.contains(row.id)))
                    // Keep a receipt that just rang (or whose mission is open) so the Open and Stop intents,
                    // which may run right after this launch's reconcile, can still find it, and so a one-off
                    // that is still alerting is never cancelled out from under its mission. Pruned after an hour.
                    let justRang = row.date.map { $0 <= Date() && Date().timeIntervalSince($0) < 3600 } ?? false
                    if activeExtra || archive.pending?.id == row.id || justRang { continue }
                    do {
                        if liveIDs.contains(row.id) { try await driver.cancel(row) }
                        _ = await mutate { $0.installed.removeAll { $0.id == row.id } }
                    } catch { failures.append("Could not remove \(row.routine.name): \(error.localizedDescription)") }
                }
                scheduling = failures.isEmpty ? (isPreview ? driver.status : "\(desired.count) alarm times registered. \(driver.status).") : failures.joined(separator:"\n")
            } catch { scheduling = error.localizedDescription }
        } while syncAgain
    }
    public func authorize() async {
        do { try await driver.request(); await synchronize() } catch { self.error = error.localizedDescription }
    }
    public func openNative(_ id: String) async throws {
        await reload()
        guard var row = archive.installed.first(where:{$0.id.uuidString == id}) else { throw LoopError.invalid("The alarm is no longer in your saved schedule.") }
        if archive.pending?.id == row.id, let pending = archive.pending { row = pending }
        // Each ringing is its own occurrence, so snoozes and checks from different days never share IDs.
        if row.occurrenceID == nil { row.occurrenceID = UUID() }
        let opened = row
        guard await mutate({ $0.pending = opened }) else { throw LoopError.invalid("Could not save the active mission.") }
        mission = opened
    }
    public func dismissNative(_ id: String) async throws {
        await reload()
        guard var row = archive.installed.first(where:{$0.id.uuidString == id}) else { return }
        if archive.pending?.id == row.id, let pending = archive.pending { row = pending }
        if row.occurrenceID == nil { row.occurrenceID = UUID() }
        let event = LoopEvent(routine:row.routine,kind:.dismissed,note:"Stopped with the system control; mission completion is unconfirmed.")
        let rowID = row.id
        guard await mutate({ $0.events.append(event); if $0.pending?.id == rowID { $0.pending = nil } }) else { throw LoopError.invalid("Could not record dismissal.") }
        if row.purpose != "followUp" && row.routine.followUpMinutes > 0 { try await extra(row,minutes:row.routine.followUpMinutes,purpose:"followUp") }
    }
    public func extra(_ row: Registration, minutes: Int, purpose: String) async throws {
        var extra = row; extra.id = ScheduleCompiler.identifier(row.occurrenceID ?? row.id,key:"\(purpose):\(row.snoozes)"); extra.date = Date().addingTimeInterval(Double(minutes)*60); extra.minute = nil; extra.weekdays = []; extra.purpose = purpose; extra.occurrenceID = row.occurrenceID ?? row.id; extra.snoozes = purpose == "snooze" ? row.snoozes + 1 : row.snoozes
        let saved = extra
        guard await mutate({ a in a.installed.removeAll { $0.id == saved.id }; a.installed.append(saved) }) else { throw LoopError.invalid("Could not save follow-up alarm.") }
        do { try await driver.schedule(extra) } catch { _ = await mutate { $0.installed.removeAll { $0.id == saved.id } }; throw error }
    }
    public func finish(_ row: Registration, kind: EventKind) async -> Bool {
        guard !busy else { return false }; busy = true; defer { busy = false }
        do {
            if kind == .snoozed {
                guard row.snoozes < row.routine.snoozeLimit else { throw LoopError.invalid("Snooze limit reached.") }
                try await extra(row,minutes:row.routine.snoozeMinutes,purpose:"snooze")
            } else if kind == .completed && row.purpose != "followUp" && row.routine.followUpMinutes > 0 { try await extra(row,minutes:row.routine.followUpMinutes,purpose:"followUp") }
            if kind == .emergencyStop || (kind == .completed && row.purpose == "followUp") {
                let liveIDs = try await driver.identifiers()
                for extra in archive.installed where extra.routine.id == row.routine.id && extra.purpose != "schedule" && extra.id != row.id {
                    if liveIDs.contains(extra.id) { try await driver.cancel(extra) }
                    _ = await mutate { $0.installed.removeAll { $0.id == extra.id } }
                }
            }
            try await driver.stop(row)
            let event = LoopEvent(routine:row.routine,kind:kind)
            guard await mutate({ a in a.events.append(event); a.pending = nil }) else { return false }
            mission = nil; return true
        } catch { self.error = error.localizedDescription; return false }
    }
    public func manual(_ r: Routine, date: Date) async { let e = LoopEvent(routine:r,kind:.manual,date:date); _ = await mutate { $0.events.append(e) } }
    public func testAlarm() async {
        do {
            var r = Routine(); r.name = "Test alarm"; r.timing = .once; r.once = Date().addingTimeInterval(10)
            let row = Registration(id:UUID(),routine:r,date:r.once,purpose:"test")
            guard await mutate({ $0.installed.append(row) }) else { return }; try await driver.schedule(row); scheduling = "Test alarm scheduled for 10 seconds from now."
        } catch { self.error = error.localizedDescription }
    }
}

public struct LoopIntentsPackage: AppIntentsPackage {}
