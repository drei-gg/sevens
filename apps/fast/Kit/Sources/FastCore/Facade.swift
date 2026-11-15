import Foundation
@_exported import FastWire

// Fast's logic is the Rust crate apps/fast/core; its Swift bindings (Generated/FastCoreFFI.swift)
// compile into this module. This file keeps the Swift spellings FastUI was written against
// (HistoryMath, ArchiveCodec, WaterHistory, ReminderPlan, Units, `FastArchive()`, `timeZoneID`)
// and only converts: Calendar to CalendarSpec, Int to the core's Int32, Watch wire types to the
// archive's. No rule or calculation lives here.

// MARK: calendar

public extension CalendarSpec {
    init(_ calendar: Calendar) {
        self.init(timeZoneId: calendar.timeZone.identifier, firstWeekday: Int32(calendar.firstWeekday))
    }
}

// MARK: records

extension FastRecord: Identifiable {}
extension JournalEntry: Identifiable {}
extension MetricEntry: Identifiable {}
extension PlannedFast: Identifiable {}
extension RestDay: Identifiable {}
extension FastPreset: Identifiable {}
extension DaySummary: Identifiable { public var id: Date { date } }
extension WaterDay: Identifiable { public var id: Date { date } }

public extension FastRecord {
    init(id: UUID = UUID(), start: Date, end: Date? = nil, goalMinutes: Int? = 960, eatingMinutes: Int = 480,
         timeZoneID: String = TimeZone.current.identifier, intention: String = "", reflection: String = "", tags: [String] = []) {
        self.init(id: id, start: start, end: end, goalMinutes: goalMinutes.map(Int32.init), eatingMinutes: Int32(eatingMinutes),
                  timeZoneId: timeZoneID, intention: intention, reflection: reflection, tags: tags)
    }
    var timeZoneID: String { get { timeZoneId } set { timeZoneId = newValue } }
    func duration(at now: Date) -> TimeInterval { fastDuration(fast: self, now: now) }
    var goalDate: Date? { fastGoalDate(fast: self) }
    var metGoal: Bool? { fastMetGoal(fast: self) }
    var searchableText: String { fastSearchableText(fast: self) }
}

public extension JournalEntry {
    init(id: UUID = UUID(), date: Date, fastID: UUID? = nil, text: String = "", mood: Int? = nil,
         energy: Int? = nil, hunger: Int? = nil, tags: [String] = []) {
        self.init(id: id, date: date, fastId: fastID, text: text, mood: mood.map(Int32.init),
                  energy: energy.map(Int32.init), hunger: hunger.map(Int32.init), tags: tags)
    }
    var fastID: UUID? { get { fastId } set { fastId = newValue } }
}

extension MetricKind: CaseIterable {
    public static var allCases: [MetricKind] { [.weight, .water, .waist, .sleep] }
}

public extension MetricKind {
    /// The archive's name: "weight", "water", "waist", "sleep".
    var rawValue: String { String(describing: self) }
    var canonicalUnit: String { metricCanonicalUnit(kind: self) }
    func contains(_ value: Double) -> Bool { metricInRange(kind: self, value: value) }
    var validRange: ClosedRange<Double> { let r = metricRange(kind: self); return r.low...r.high }
}

public extension MetricEntry {
    init(id: UUID = UUID(), date: Date, kind: MetricKind, value: Double, externalID: String? = nil) {
        self.init(id: id, date: date, kind: kind, value: value, externalId: externalID)
    }
    var externalID: String? { get { externalId } set { externalId = newValue } }
}

public extension FastPreset {
    init(id: UUID = UUID(), name: String, goalMinutes: Int?, eatingMinutes: Int) {
        self.init(id: id, name: name, goalMinutes: goalMinutes.map(Int32.init), eatingMinutes: Int32(eatingMinutes))
    }
    static var defaults: [FastPreset] { defaultPresets() }
}

public extension PlannedFast {
    init(id: UUID = UUID(), start: Date, goalMinutes: Int? = 960, eatingMinutes: Int = 480, intention: String = "") {
        self.init(id: id, start: start, goalMinutes: goalMinutes.map(Int32.init), eatingMinutes: Int32(eatingMinutes), intention: intention)
    }
}

public extension RestDay {
    /// A rest day on `date`, with a new identifier.
    static func on(_ date: Date, note: String = "") -> RestDay { RestDay(id: UUID(), date: date, note: note) }
}

public extension TrackerSettings {
    init() { self = defaultTrackerSettings() }
}

public extension WaterSettings {
    init() { self = defaultWaterSettings() }
    func validate() throws { try waterSettingsValidate(settings: self) }
    var reminderHours: [Int] { waterReminderHours(settings: self).map(Int.init) }
}

public enum Units {
    public static func display(_ value: Double, kind: MetricKind, settings: TrackerSettings) -> Double {
        unitsDisplay(value: value, kind: kind, settings: settings)
    }
    public static func canonical(_ value: Double, kind: MetricKind, settings: TrackerSettings) -> Double {
        unitsCanonical(value: value, kind: kind, settings: settings)
    }
}

// MARK: archive

public extension FastArchive {
    init() { self = newArchive() }
    var active: FastRecord? { archiveActive(archive: self) }
    var latestCompleted: FastRecord? { archiveLatestCompleted(archive: self) }
    func validate(now: Date) throws { try archiveValidate(archive: self, now: now) }
    /// Inserts or replaces `fast` and validates; unchanged on failure.
    mutating func save(_ fast: FastRecord, now: Date) throws { self = try archiveSave(archive: self, fast: fast, now: now) }
    /// Journal entries linked to the fast are kept and unlinked.
    mutating func deleteFast(_ id: UUID) { self = archiveDeleteFast(archive: self, id: id) }
}

public enum ArchiveCodec {
    public static let maxBytes = Int(archiveMaxBytes())
    public static func encode(_ archive: FastArchive) throws -> Data { try archiveEncode(archive: archive) }
    public static func decode(_ data: Data, now: Date) throws -> FastArchive { try archiveDecode(data: data, now: now) }
    /// Existing settings win; a record present in both must match exactly.
    public static func merge(_ incoming: FastArchive, into current: FastArchive, now: Date) throws -> ImportPreview {
        try archiveMerge(incoming: incoming, current: current, now: now)
    }
    public static func csv(_ archive: FastArchive) -> String { archiveCsv(archive: archive) }
}

public extension ImportPreview {
    var totalAddedCount: Int { Int(totalAdded) }
}

// MARK: history

public extension DaySummary {
    var fastIDs: [UUID] { fastIds }
}

public enum HistoryMath {
    public static func overlap(start: Date, end: Date, interval: DateInterval) -> TimeInterval {
        historyOverlap(start: start, end: end, intervalStart: interval.start, intervalEnd: interval.end)
    }
    public static func day(_ date: Date, archive: FastArchive, calendar: Calendar, now: Date) -> DaySummary {
        historyDay(date: date, archive: archive, calendar: CalendarSpec(calendar), now: now)
            ?? DaySummary(date: calendar.startOfDay(for: date), seconds: 0, fastIds: [], journalCount: 0, isRestDay: false, planCount: 0)
    }
    public static func days(in interval: DateInterval, archive: FastArchive, calendar: Calendar, now: Date) -> [DaySummary] {
        historyDays(start: interval.start, end: interval.end, archive: archive, calendar: CalendarSpec(calendar), now: now)
    }
    public static func summary(in interval: DateInterval, archive: FastArchive, now: Date) -> PeriodSummary {
        historySummary(start: interval.start, end: interval.end, archive: archive, now: now)
    }
    public static func monthGrid(containing date: Date, calendar: Calendar) -> [Date] {
        historyMonthGrid(date: date, calendar: CalendarSpec(calendar))
    }
    public static func matches(_ fast: FastRecord, query: String) -> Bool { historyMatches(fast: fast, query: query) }
    public static func arcs(on date: Date, archive: FastArchive, calendar: Calendar, now: Date) -> [DayArc] {
        dayArcs(date: date, archive: archive, calendar: CalendarSpec(calendar), now: now)
    }
    public static func dialPosition(_ date: Date, calendar: Calendar) -> Double {
        FastCore.dialPosition(date: date, calendar: CalendarSpec(calendar))
    }
}

public extension DayArc {
    typealias Kind = DayArcKind
    var fastID: UUID { fastId }
}

// MARK: water

public enum WaterHistory {
    public static func entries(_ metrics: [MetricEntry], on day: Date, calendar: Calendar = .current) -> [MetricEntry] {
        waterEntries(metrics: metrics, day: day, calendar: CalendarSpec(calendar))
    }
    public static func total(_ metrics: [MetricEntry], on day: Date, calendar: Calendar = .current) -> Double {
        waterTotal(metrics: metrics, day: day, calendar: CalendarSpec(calendar))
    }
    public static func days(_ metrics: [MetricEntry], ending day: Date, count: Int, calendar: Calendar = .current) -> [WaterDay] {
        waterDays(metrics: metrics, day: day, count: Int32(clamping: count), calendar: CalendarSpec(calendar))
    }
}

// MARK: reminders

public enum ReminderPlan {
    public static func events(archive: FastArchive, now: Date) -> [ReminderEvent] { reminderEvents(archive: archive, now: now) }
}

extension ReminderEvent: Identifiable {}

// MARK: the Watch and widgets

public extension TimerSnapshot {
    init(archive: FastArchive) {
        let active = archive.active
        self.init(fastID: active?.id, start: active?.start, goal: active?.goalDate)
    }
}

public extension FastWatchSnapshot {
    init(archive: FastArchive, canCreateRecords: Bool, now: Date = Date(), calendar: Calendar = .current) {
        let active = archive.active
        self.init(revision: archive.companionRevision, generatedAt: now, activeID: active?.id, start: active?.start,
                  goal: active?.goalDate, waterMilliliters: WaterHistory.total(archive.metrics, on: now, calendar: calendar),
                  canCreateRecords: canCreateRecords)
    }
}

public extension FastArchive {
    /// Applies a Watch request inside the store's transaction (rules in the core). A replay of a
    /// committed request leaves the archive unchanged.
    mutating func applyWatch(_ request: FastWatchRequest, canCreateRecords: Bool, now: Date) throws {
        let action: CompanionAction = switch request.action {
        case .refresh: .refresh
        case .start: .start
        case .end: .end
        case .water: .water
        }
        let companion = CompanionRequest(version: Int32(clamping: request.version), id: request.id, sentAt: request.sentAt,
                                         action: action, revision: request.revision, activeId: request.activeID)
        do {
            self = try watchApply(archive: self, request: companion, canCreateRecords: canCreateRecords,
                                  timeZoneId: TimeZone.current.identifier, now: now)
        } catch let failure as CompanionFailure {
            throw FastWatchFailure(failure)
        }
    }
}

extension FastWatchFailure {
    init(_ failure: CompanionFailure) {
        self = switch failure {
        case .Unsupported: .unsupported
        case .Malformed: .malformed
        case .Expired: .expired
        case .Stale: .stale
        case .Locked: .locked
        case .Unavailable: .unavailable
        case .SaveFailed: .saveFailed
        }
    }
}

// MARK: the glass

public enum LiquidGeometry {
    public static func circleSurfaceOffset(fraction: Double) -> Double { FastCore.circleSurfaceOffset(fraction: fraction) }
}
