import Foundation

public enum LoopError: Error, LocalizedError, Equatable {
    case invalid(String)
    public var errorDescription: String? { if case let .invalid(message) = self { return message }; return nil }
}
public enum RoutineKind: String, Codable, CaseIterable, Sendable { case alarm, loop }
public enum Delivery: String, Codable, CaseIterable, Sendable { case alarm, reminder }
public enum MissionKind: String, Codable, CaseIterable, Sendable { case none, arithmetic, typing, code, memory, shake, walk, numberOrder, colorWord }
public enum AlarmTone: String, Codable, CaseIterable, Sendable { case system, chime, pulse, bright }
public enum Timing: String, Codable, CaseIterable, Sendable { case weekly, once, window }
public struct TimeSlot: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID = UUID()
    public var minute: Int
    public var weekdays: [Int]
    public init(minute: Int = 420, weekdays: [Int] = [2,3,4,5,6]) { self.minute = minute; self.weekdays = weekdays }
}
public struct Routine: Codable, Equatable, Identifiable, Sendable {
    public var id = UUID()
    public var name: String = "Wake up"
    public var reason: String? = nil
    public var group: String? = nil
    public var tone: AlarmTone? = nil
    public var kind: RoutineKind = .alarm
    public var enabled = true
    public var delivery: Delivery = .alarm
    public var timing: Timing = .weekly
    public var slots: [TimeSlot] = [TimeSlot()]
    public var once = Date().addingTimeInterval(3600)
    public var windowStart = 540
    public var windowEnd = 1020
    public var intervalMinutes = 60
    public var windowDays = [2,3,4,5,6]
    public var mission: MissionKind = .none
    public var phrase = "I am awake"
    public var code = ""
    public var challengeLevel: Int? = nil
    public var difficulty: Int { min(3, max(1, challengeLevel ?? 1)) }
    public var mathCount = 3
    public var snoozeMinutes = 5
    public var snoozeLimit = 3
    public var followUpMinutes = 0
    public init(kind: RoutineKind = .alarm) { self.kind = kind; if kind == .loop { name = "Take a break"; delivery = .reminder } }
    public func validate() throws {
        func require(_ value: Bool, _ message: String) throws { if !value { throw LoopError.invalid(message) } }
        try require((reason?.count ?? 0) <= 300 && (group?.count ?? 0) <= 60, "Keep the reason under 300 characters and the group under 60.")
        try require(!name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && name.count <= 100, "Give the routine a name of up to 100 characters.")
        try require(slots.count <= 48 && !slots.isEmpty, "Use between 1 and 48 shared times.")
        try require(Set(slots.map(\.id)).count == slots.count, "Duplicate time identifiers.")
        for slot in slots {
            try require((0..<1440).contains(slot.minute) && !slot.weekdays.isEmpty && Set(slot.weekdays).count == slot.weekdays.count && slot.weekdays.allSatisfy { (1...7).contains($0) }, "Choose a valid time and at least one weekday for each shared time.")
        }
        try require((0..<1440).contains(windowStart) && (0..<1440).contains(windowEnd) && windowStart != windowEnd && (5...720).contains(intervalMinutes), "Choose different window endpoints and an interval from 5 to 720 minutes.")
        try require(!windowDays.isEmpty && Set(windowDays).count == windowDays.count && windowDays.allSatisfy { (1...7).contains($0) }, "Choose at least one weekday.")
        try require(once.timeIntervalSince1970.isFinite, "Invalid date.")
        try require((1...10).contains(mathCount) && (1...30).contains(snoozeMinutes) && (0...10).contains(snoozeLimit) && (0...30).contains(followUpMinutes), "Mission, snooze or follow-up settings are out of range.")
        try require(challengeLevel == nil || (1...3).contains(challengeLevel!), "Choose a difficulty from 1 to 3.")
        try require(phrase.count <= 200 && code.utf8.count <= 4096, "Mission text is too long.")
        if mission == .typing { try require(!phrase.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "Enter a phrase for the typing mission.") }
        if mission == .code { try require(!code.isEmpty, "Scan a code before enabling this mission.") }
        if delivery == .reminder { try require(mission == .none && followUpMinutes == 0, "Missions and wake-up checks require alarm delivery.") }
    }
}
public struct Registration: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var routine: Routine
    public var minute: Int?
    public var weekdays: [Int]
    public var date: Date?
    public var purpose: String = "schedule"
    public var occurrenceID: UUID?
    public var snoozes = 0
    public init(id: UUID, routine: Routine, minute: Int? = nil, weekdays: [Int] = [], date: Date? = nil, purpose: String = "schedule", occurrenceID: UUID? = nil, snoozes: Int = 0) {
        self.id = id; self.routine = routine; self.minute = minute; self.weekdays = weekdays; self.date = date; self.purpose = purpose; self.occurrenceID = occurrenceID; self.snoozes = snoozes
    }
}
public enum EventKind: String, Codable, CaseIterable, Sendable { case completed, dismissed, snoozed, emergencyStop, manual, followUpScheduled }
public struct LoopEvent: Codable, Equatable, Identifiable, Sendable {
    public var id = UUID()
    public var routineID: UUID
    public var name: String
    public var date: Date
    public var kind: EventKind
    public var note: String
    public init(routine: Routine, kind: EventKind, date: Date = Date(), note: String = "") { routineID = routine.id; name = routine.name; self.kind = kind; self.date = date; self.note = note }
}
public struct LoopArchive: Codable, Equatable, Sendable {
    public var version = 1
    /// Optional so archives created before reusable defaults remain readable.
    public var alarmDefaults: AlarmDefaults?
    public var routines: [Routine] = []
    public var events: [LoopEvent] = []
    /// Receipts survive relaunch and let reconciliation cancel orphan native alarms.
    public var installed: [Registration] = []
    public var pending: Registration?
    public init() {}
    public func validate() throws {
        guard version == 1 else { throw LoopError.invalid("This backup needs a newer app. Your file has not been changed.") }
        try alarmDefaults?.validate()
        guard routines.count <= 100, events.count <= 100_000, installed.count <= 1024, Set(routines.map(\.id)).count == routines.count, Set(events.map(\.id)).count == events.count, Set(installed.map(\.id)).count == installed.count else { throw LoopError.invalid("Invalid or oversized archive.") }
        for r in routines { try r.validate() }
        for e in events { guard e.date.timeIntervalSince1970.isFinite, e.name.count <= 100, e.note.count <= 1000 else { throw LoopError.invalid("Invalid history record.") } }
        for r in installed + (pending.map { [$0] } ?? []) {
            try r.routine.validate()
            guard r.date?.timeIntervalSince1970.isFinite != false, r.minute.map({ (0..<1440).contains($0) }) != false, r.weekdays.allSatisfy({ (1...7).contains($0) }), (0...10).contains(r.snoozes) else { throw LoopError.invalid("Invalid alarm receipt.") }
        }
    }
}
