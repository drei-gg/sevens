import Foundation

public struct FastRecord: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var start: Date
    public var end: Date?
    public var goalMinutes: Int?
    public var eatingMinutes: Int
    public var timeZoneID: String
    public var intention: String
    public var reflection: String
    public var tags: [String]

    public init(id: UUID = UUID(), start: Date, end: Date? = nil, goalMinutes: Int? = 960,
                eatingMinutes: Int = 480, timeZoneID: String = TimeZone.current.identifier,
                intention: String = "", reflection: String = "", tags: [String] = []) {
        self.id = id; self.start = start; self.end = end; self.goalMinutes = goalMinutes
        self.eatingMinutes = eatingMinutes; self.timeZoneID = timeZoneID
        self.intention = intention; self.reflection = reflection; self.tags = tags
    }

    public func duration(at now: Date) -> TimeInterval { max(0, (end ?? now).timeIntervalSince(start)) }
    public var goalDate: Date? { goalMinutes.map { start.addingTimeInterval(Double($0) * 60) } }
    public var metGoal: Bool? {
        guard let end, let goalDate else { return nil }
        return end >= goalDate
    }
    public var searchableText: String { ([intention, reflection] + tags).joined(separator: " ") }
}

public struct JournalEntry: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var date: Date
    public var fastID: UUID?
    public var text: String
    public var mood: Int?
    public var energy: Int?
    public var hunger: Int?
    public var tags: [String]

    public init(id: UUID = UUID(), date: Date, fastID: UUID? = nil, text: String = "", mood: Int? = nil,
                energy: Int? = nil, hunger: Int? = nil, tags: [String] = []) {
        self.id = id; self.date = date; self.fastID = fastID; self.text = text
        self.mood = mood; self.energy = energy; self.hunger = hunger; self.tags = tags
    }
}

public enum MetricKind: String, Codable, CaseIterable, Sendable {
    case weight, water, waist, sleep
    public var canonicalUnit: String {
        switch self { case .weight: "kg"; case .water: "mL"; case .waist: "cm"; case .sleep: "h" }
    }
    public var validRange: ClosedRange<Double> {
        switch self { case .weight: 1...700; case .water: 1...10_000; case .waist: 1...400; case .sleep: 0.01...24 }
    }
}

public struct MetricEntry: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var date: Date
    public var kind: MetricKind
    /// Always stored in canonical units; display conversions never rewrite history.
    public var value: Double
    public var externalID: String?
    public init(id: UUID = UUID(), date: Date, kind: MetricKind, value: Double, externalID: String? = nil) {
        self.id = id; self.date = date; self.kind = kind; self.value = value; self.externalID = externalID
    }
}

public struct FastPreset: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var name: String
    public var goalMinutes: Int?
    public var eatingMinutes: Int
    public init(id: UUID = UUID(), name: String, goalMinutes: Int?, eatingMinutes: Int) {
        self.id = id; self.name = name; self.goalMinutes = goalMinutes; self.eatingMinutes = eatingMinutes
    }
    public static let defaults: [FastPreset] = [12, 14, 16, 18, 20].enumerated().map { index, hours in
        FastPreset(id: UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", index + 1))!,
                   name: "\(hours):\(24 - hours)", goalMinutes: hours * 60, eatingMinutes: (24 - hours) * 60)
    } + [FastPreset(id: UUID(uuidString: "00000000-0000-0000-0000-000000000006")!,
                    name: "Open ended", goalMinutes: nil, eatingMinutes: 480)]
}

public struct PlannedFast: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var start: Date
    public var goalMinutes: Int?
    public var eatingMinutes: Int
    public var intention: String
    public init(id: UUID = UUID(), start: Date, goalMinutes: Int? = 960, eatingMinutes: Int = 480, intention: String = "") {
        self.id = id; self.start = start; self.goalMinutes = goalMinutes
        self.eatingMinutes = eatingMinutes; self.intention = intention
    }
}

public struct RestDay: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var date: Date
    public var note: String
    public init(id: UUID = UUID(), date: Date, note: String = "") { self.id = id; self.date = date; self.note = note }
}

public struct TrackerSettings: Codable, Equatable, Sendable {
    public var usePounds = false
    public var useFluidOunces = false
    public var showWeight = true
    public var showEatingWindow = true
    public var liveActivity = false
    public var goalReminder = false
    public var eatingReminder = false
    public var scheduleReminders = false
    /// Calendar weekdays: 1 Sunday ... 7 Saturday. Empty means no repeating reminder.
    public var reminderWeekdays: [Int] = []
    public var reminderHour = 20
    public var reminderMinute = 0
    public init() {}
    enum CodingKeys: String, CodingKey {
        case usePounds, useFluidOunces, showWeight, showEatingWindow, liveActivity, goalReminder, eatingReminder, scheduleReminders
        case reminderWeekdays, reminderHour, reminderMinute
    }
    public init(from decoder: any Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        usePounds = try c.decodeIfPresent(Bool.self, forKey: .usePounds) ?? false
        useFluidOunces = try c.decodeIfPresent(Bool.self, forKey: .useFluidOunces) ?? false
        showWeight = try c.decodeIfPresent(Bool.self, forKey: .showWeight) ?? true
        showEatingWindow = try c.decodeIfPresent(Bool.self, forKey: .showEatingWindow) ?? true
        liveActivity = try c.decodeIfPresent(Bool.self, forKey: .liveActivity) ?? false
        goalReminder = try c.decodeIfPresent(Bool.self, forKey: .goalReminder) ?? false
        eatingReminder = try c.decodeIfPresent(Bool.self, forKey: .eatingReminder) ?? false
        scheduleReminders = try c.decodeIfPresent(Bool.self, forKey: .scheduleReminders) ?? false
        reminderWeekdays = try c.decodeIfPresent([Int].self, forKey: .reminderWeekdays) ?? []
        reminderHour = try c.decodeIfPresent(Int.self, forKey: .reminderHour) ?? 20
        reminderMinute = try c.decodeIfPresent(Int.self, forKey: .reminderMinute) ?? 0
    }
}

public enum Units {
    public static let poundsPerKilogram = 2.2046226218487757
    public static let millilitersPerFluidOunce = 29.5735295625
    public static func display(_ value: Double, kind: MetricKind, settings: TrackerSettings) -> Double {
        switch kind {
        case .weight: settings.usePounds ? value * poundsPerKilogram : value
        case .water: settings.useFluidOunces ? value / millilitersPerFluidOunce : value
        default: value
        }
    }
    public static func canonical(_ value: Double, kind: MetricKind, settings: TrackerSettings) -> Double {
        switch kind {
        case .weight: settings.usePounds ? value / poundsPerKilogram : value
        case .water: settings.useFluidOunces ? value * millilitersPerFluidOunce : value
        default: value
        }
    }
}
