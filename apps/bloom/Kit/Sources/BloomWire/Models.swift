import Foundation

public struct Profile: Codable, Sendable, Equatable {
  public var mode = "cycle"
  public var typical_cycle = 28
  public var typical_period = 5
  public var lactating = false
  public var discreet = true
  public var period_reminder = false
  public var watch_enabled = false
  public var widgets_enabled = false
  public init() {}
}
public struct DayLog: Codable, Sendable, Equatable, Identifiable {
  public var id = UUID().uuidString.lowercased()
  public var day = BloomDate.day(Date())
  public var flow = "none"
  public var period_start = false
  public var symptoms: [String: Int] = [:]
  public var tags: [String] = []
  public var notes = ""
  public var bbt: Double?
  public var mucus = "none"
  public var ovulation_test = "none"
  public var pregnancy_test = "none"
  public init() {}
}
public struct Contact: Codable, Sendable, Equatable, Identifiable {
  public var id = UUID().uuidString.lowercased()
  public var day = BloomDate.day(Date())
  public var at = Int64(Date().timeIntervalSince1970)
  public var activity = "Sex"
  public var protection = "Not specified"
  public var libido: Int?
  public var orgasm: Bool?
  public var pain: Int?
  public var notes = ""
  public var tags: [String] = []
  public init() {}
}
public struct Plan: Codable, Sendable, Equatable, Identifiable {
  public var id = UUID().uuidString.lowercased()
  public var name = ""
  public var method = "pill"
  public var hormonal = true
  public var start = BloomDate.day(Date())
  public var end: String?
  public var minute = 480
  public var zone = TimeZone.current.identifier
  public var fixed_zone = false
  public var pattern = "pack"
  public var active_days = 21
  public var break_days = 7
  public var every_days = 1
  public var weekdays = [1, 2, 3, 4, 5, 6, 7]
  public var remind_break = false
  public var enabled = true
  public var initial_stock: Int?
  public var notes = ""
  public init() {}
}
public struct Dose: Codable, Sendable, Equatable, Identifiable {
  public var id = ""
  public var plan_id = ""
  public var day = ""
  public var scheduled: Int64 = 0
  public var status = "taken"
  public var recorded = Int64(Date().timeIntervalSince1970)
  public var snooze_until: Int64?
  public var notes = ""
  public init() {}
  public init(_ occurrence: Occurrence, status: String) {
    id = occurrence.id
    plan_id = occurrence.plan_id
    day = occurrence.day
    scheduled = occurrence.at
    self.status = status
    if status == "snoozed" { snooze_until = Int64(Date().timeIntervalSince1970) + 600 }
  }
}
public struct Refill: Codable, Sendable, Equatable, Identifiable {
  public var id = UUID().uuidString.lowercased()
  public var plan_id = ""
  public var day = BloomDate.day(Date())
  public var quantity = 28
  public init() {}
}
public struct Appointment: Codable, Sendable, Equatable, Identifiable {
  public var id = UUID().uuidString.lowercased()
  public var title = ""
  public var at = Int64(Date().addingTimeInterval(3600).timeIntervalSince1970)
  public var remind_minutes = 60
  public var notes = ""
  public var kind = "Appointment"
  public init() {}
}
public struct Pregnancy: Codable, Sendable, Equatable, Identifiable {
  public var id = UUID().uuidString.lowercased()
  public var start = BloomDate.day(Date())
  public var due = BloomDate.day(
    Calendar.current.date(byAdding: .day, value: 280, to: Date()) ?? Date())
  public var ended: String?
  public var outcome = ""
  public var notes = ""
  public init() {}
}
public struct Receipt: Codable, Sendable, Equatable {
  public var id: String
  public var digest: String
  public var at: Int64
}
public struct Archive: Codable, Sendable, Equatable {
  public var schema = 1
  public var revision: UInt64 = 0
  public var profile = Profile()
  public var days: [DayLog] = []
  public var contacts: [Contact] = []
  public var plans: [Plan] = []
  public var doses: [Dose] = []
  public var refills: [Refill] = []
  public var appointments: [Appointment] = []
  public var pregnancies: [Pregnancy] = []
  public var receipts: [Receipt] = []
  public init() {}
}
public struct Occurrence: Codable, Sendable, Identifiable, Equatable {
  public var id: String
  public var plan_id: String
  public var day: String
  public var at: Int64
  public var title: String
  public var phase: String
  public var status: String
}
public struct Reminder: Codable, Sendable, Identifiable {
  public var id: String
  public var at: Int64
  public var title: String
  public var body: String
  public var dose: Occurrence?
}
public struct Cycle: Codable, Sendable, Identifiable {
  public var id: String { start }
  public var start: String
  public var length: Int?
  public var bleeding_days: Int
}
public struct SymptomStat: Codable, Sendable, Identifiable {
  public var id: String { name }
  public var name: String
  public var count: Int
  public var logged_days: Int
  public var average: Double
  public var preperiod_count: Int
}
public struct Stock: Codable, Sendable, Identifiable {
  public var id: String { plan_id }
  public var plan_id: String
  public var name: String
  public var remaining: Int
}
public struct Dashboard: Codable, Sendable {
  public var today: String
  public var cycle_day: Int?
  public var cycles: [Cycle]
  public var lengths: [Int]
  public var next_period: String?
  public var earliest: String?
  public var latest: String?
  public var prediction_note: String
  public var fertile_start: String?
  public var fertile_end: String?
  public var symptoms: [SymptomStat]
  public var today_doses: [Occurrence]
  public var reminders: [Reminder]
  public var coverage_until: Int64?
  public var reminder_total: Int
  public var taken: Int
  public var skipped: Int
  public var stock: [Stock]
  public var pregnancy_week: Int?
  public var pregnancy_day: Int?
}
public enum JSONValue: Codable, Sendable {
  case null
  case bool(Bool)
  case number(Double)
  case string(String)
  case array([JSONValue])
  case object([String: JSONValue])
  public init(from decoder: Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() {
      self = .null
    } else if let b = try? c.decode(Bool.self) {
      self = .bool(b)
    } else if let n = try? c.decode(Double.self) {
      self = .number(n)
    } else if let s = try? c.decode(String.self) {
      self = .string(s)
    } else if let a = try? c.decode([JSONValue].self) {
      self = .array(a)
    } else {
      self = .object(try c.decode([String: JSONValue].self))
    }
  }
  public func encode(to encoder: Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .null: try c.encodeNil()
    case .bool(let v): try c.encode(v)
    case .number(let v): try c.encode(v)
    case .string(let v): try c.encode(v)
    case .array(let v): try c.encode(v)
    case .object(let v): try c.encode(v)
    }
  }
}
public struct Command: Codable, Sendable {
  public var op: String
  public var record: JSONValue?
  public var id: String?
  public var request_id = UUID().uuidString.lowercased()
  public var expected_revision: UInt64?
  public var created_at = Int64(Date().timeIntervalSince1970)
  public init<T: Encodable>(op: String, record: T, revision: UInt64? = nil) throws {
    self.op = op
    self.record = try BloomCodec.decode(JSONValue.self, from: BloomCodec.encode(record))
    expected_revision = revision
  }
  public init(delete op: String, id: String, revision: UInt64) {
    self.op = op
    self.id = id
    expected_revision = revision
  }
}
public enum BloomCodec {
  public static func encode<T: Encodable>(_ value: T) throws -> Data {
    let e = JSONEncoder()
    e.outputFormatting = [.sortedKeys]
    return try e.encode(value)
  }
  public static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
    try JSONDecoder().decode(type, from: data)
  }
  public static func string<T: Encodable>(_ value: T) throws -> String {
    String(decoding: try encode(value), as: UTF8.self)
  }
}
public enum BloomDate {
  public static func day(_ date: Date, zone: TimeZone = .current) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.calendar = Calendar(identifier: .gregorian)
    f.timeZone = zone
    f.dateFormat = "yyyy-MM-dd"
    return f.string(from: date)
  }
  public static func date(_ day: String) -> Date {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.calendar = Calendar(identifier: .gregorian)
    f.timeZone = .current
    f.dateFormat = "yyyy-MM-dd HH:mm"
    return f.date(from: day + " 12:00") ?? Date(timeIntervalSince1970: 0)
  }
}
public struct WatchSnapshot: Codable, Sendable {
  public var version = 1
  public var generated: Int64
  public var enabled: Bool
  public var cycle_day: Int?
  public var doses: [Occurrence]
  public var can_add: Bool
  public init(enabled: Bool, cycleDay: Int?, doses: [Occurrence], canAdd: Bool) {
    generated = Int64(Date().timeIntervalSince1970)
    self.enabled = enabled
    cycle_day = cycleDay
    self.doses = doses
    can_add = canAdd
  }
}
public struct WatchRequest: Codable, Sendable {
  public var version = 1
  public var id = UUID().uuidString.lowercased()
  public var command: Command?
  public init(command: Command? = nil) { self.command = command }
}
public struct WatchReply: Codable, Sendable {
  public var version = 1
  public var request_id: String?
  public var snapshot: WatchSnapshot?
  public var error: String?
  public init(requestID: String? = nil, snapshot: WatchSnapshot? = nil, error: String? = nil) {
    request_id = requestID
    self.snapshot = snapshot
    self.error = error
  }
}
