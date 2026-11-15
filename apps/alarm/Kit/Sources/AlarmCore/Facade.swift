import Foundation
@_exported import AlarmStore

// Alarm & Loops' logic is the Rust crate apps/alarm/core; its Swift bindings
// (Generated/AlarmCoreFFI.swift) compile into this module. This file keeps the Swift spellings
// AlarmUI was written against (BackupCodec, ScheduleCompiler, MissionCheck, `Routine()`,
// `routine.validate()`, `occurrenceID`) and only converts: Calendar to CalendarSpec, Int to the
// core's Int32, the core's LoopError to a sentence. No rule or calculation lives here.

// MARK: errors

/// What the app shows when something is refused: one sentence for the person. The core's own
/// `LoopError` (generated) arrives here through the façade, so the app never shows its debug text.
public enum LoopFailure: Error, LocalizedError, Equatable, Sendable {
    case invalid(String)
    public var errorDescription: String? { if case let .invalid(message) = self { return message }; return nil }

    public init(_ error: LoopError) {
        switch error {
        case let .Invalid(reason): self = .invalid(reason)
        // What Foundation's JSONDecoder said for the same bytes.
        case .Unreadable: self = .invalid("The data couldn’t be read because it isn’t in the correct format.")
        }
    }

    /// Runs a core call, turning its `LoopError` into an `LoopFailure`.
    public static func sentence<T>(_ call: () throws -> T) throws -> T {
        do { return try call() } catch let error as LoopError { throw LoopFailure(error) }
    }
}

// MARK: enums: the archive's names

extension RoutineKind: CaseIterable { public static var allCases: [RoutineKind] { [.alarm, .loop] } }
extension Delivery: CaseIterable { public static var allCases: [Delivery] { [.alarm, .reminder] } }
extension AlarmTone: CaseIterable { public static var allCases: [AlarmTone] { [.system, .chime, .pulse, .bright] } }
extension Timing: CaseIterable { public static var allCases: [Timing] { [.weekly, .once, .window] } }
extension EventKind: CaseIterable {
    public static var allCases: [EventKind] { [.completed, .dismissed, .snoozed, .emergencyStop, .manual, .followUpScheduled] }
}
extension MissionKind: CaseIterable {
    public static var allCases: [MissionKind] { [.none, .arithmetic, .typing, .code, .memory, .shake, .walk, .numberOrder, .colorWord] }
}
extension ColorWord: CaseIterable { public static var allCases: [ColorWord] { [.red, .blue, .green, .orange] } }

/// The name each case has in the archive ("numberOrder", "emergencyStop"), which is its Swift name.
public protocol ArchiveName: CaseIterable {}
public extension ArchiveName {
    var rawValue: String { String(describing: self) }
    init?(rawValue: String) {
        guard let match = Self.allCases.first(where: { $0.rawValue == rawValue }) else { return nil }
        self = match
    }
}
extension RoutineKind: ArchiveName {}
extension Delivery: ArchiveName {}
extension AlarmTone: ArchiveName {}
extension Timing: ArchiveName {}
extension EventKind: ArchiveName {}
extension MissionKind: ArchiveName {}
extension ColorWord: ArchiveName {}

// MARK: records

extension TimeSlot: Identifiable {}
extension Routine: Identifiable {}
extension Registration: Identifiable {}
extension LoopEvent: Identifiable {}

public extension TimeSlot {
    /// 07:00 on weekdays unless told otherwise, with a new identifier.
    init(minute: Int32? = nil, weekdays: [Int32]? = nil) {
        let slot = defaultTimeSlot()
        self = newTimeSlot(minute: minute ?? slot.minute, weekdays: weekdays ?? slot.weekdays)
    }
}

public extension Routine {
    /// A new routine; a one-off defaults to an hour from now.
    init(kind: RoutineKind = .alarm) { self = newRoutine(kind: kind, now: Date()) }
    /// The challenge level from 1 to 3; older backups without one get 1.
    var difficulty: Int { Int(routineDifficulty(routine: self)) }
    func validate() throws { try LoopFailure.sentence { try routineValidate(routine: self) } }
}

public extension Registration {
    init(id: UUID, routine: Routine, minute: Int32? = nil, weekdays: [Int32] = [], date: Date? = nil,
         purpose: String? = nil, occurrenceID: UUID? = nil, snoozes: Int32? = nil) {
        var row = newRegistration(id: id, routine: routine)
        row.minute = minute; row.weekdays = weekdays; row.date = date; row.occurrenceId = occurrenceID
        if let purpose { row.purpose = purpose }
        if let snoozes { row.snoozes = snoozes }
        self = row
    }
    var occurrenceID: UUID? { get { occurrenceId } set { occurrenceId = newValue } }
}

public extension LoopEvent {
    init(routine: Routine, kind: EventKind, date: Date = Date(), note: String = "") {
        self = newLoopEvent(routine: routine, kind: kind, date: date, note: note)
    }
    var routineID: UUID { get { routineId } set { routineId = newValue } }
}

public extension AlarmDefaults {
    /// Defaults only affect newly created alarms. Existing schedules keep their own settings.
    init() { self = defaultAlarmDefaults() }
    func validate() throws { try LoopFailure.sentence { try alarmDefaultsValidate(defaults: self) } }
    /// A new routine; only alarms take the defaults.
    func routine(kind: RoutineKind = .alarm) -> Routine { alarmDefaultsRoutine(defaults: self, kind: kind, now: Date()) }
}

public extension LoopArchive {
    init() { self = newLoopArchive() }
    func validate() throws { try LoopFailure.sentence { try loopArchiveValidate(archive: self) } }
}

// MARK: backups

public enum BackupCodec {
    public static let maxBytes = Int(backupMaxBytes())
    public static func encode(_ archive: LoopArchive) throws -> Data { try LoopFailure.sentence { try backupEncode(archive: archive) } }
    public static func decode(_ data: Data) throws -> LoopArchive { try LoopFailure.sentence { try backupDecode(data: data) } }
    /// Imports are dormant. Receipts and pending missions belong to the original device only.
    public static func merge(_ incoming: LoopArchive, into current: LoopArchive) throws -> LoopArchive {
        try LoopFailure.sentence { try backupMerge(incoming: incoming, current: current) }
    }
    public static func csv(_ archive: LoopArchive) -> String { backupCsv(archive: archive) }
}

// MARK: schedules

public extension CalendarSpec {
    init(_ calendar: Calendar) {
        self.init(timeZoneId: calendar.timeZone.identifier, firstWeekday: Int32(calendar.firstWeekday))
    }
}

public enum ScheduleCompiler {
    /// A stable UUID in the routine namespace.
    public static func identifier(_ routine: UUID, key: String) -> UUID { scheduleIdentifier(routine: routine, key: key) }
    public static func registrations(_ routine: Routine, now: Date = Date()) throws -> [Registration] {
        try LoopFailure.sentence { try scheduleRegistrations(routine: routine, now: now) }
    }
    public static func occurrences(_ routine: Routine, after now: Date, days: Int = 7, calendar: Calendar = .current) throws -> [Date] {
        try LoopFailure.sentence {
            try scheduleOccurrences(routine: routine, now: now, days: Int32(clamping: days), calendar: CalendarSpec(calendar))
        }
    }
}

// MARK: missions

public extension ArithmeticChallenge {
    /// Two numbers from 2 to 25.
    init() { self = newArithmeticChallenge() }
    func accepts(_ answer: String) -> Bool { arithmeticAccepts(challenge: self, answer: answer) }
}

public enum MissionCheck {
    public static func phrase(_ actual: String, expected: String) -> Bool { missionCheckPhrase(actual: actual, expected: expected) }
    public static func code(_ actual: String, expected: String) -> Bool { missionCheckCode(actual: actual, expected: expected) }
}

public extension ColorWord {
    static func accepts(_ answer: ColorWord, ink: ColorWord) -> Bool { colorWordAccepts(answer: answer, ink: ink) }
}

public extension SequenceChallenge {
    /// Whether the tap was right; a wrong tap starts over. The state lives in the core object.
    @discardableResult func tap(_ tile: Int) -> Bool { tap(tile: Int32(clamping: tile)) }
}
