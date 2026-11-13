import Foundation

/// Preferences, not intake recommendations. Canonical mL keeps unit changes lossless.
public struct WaterSettings: Codable, Equatable, Sendable {
    public var goalMilliliters: Double?
    public var quickAddMilliliters: Double = 250
    public var remindersEnabled = false
    public var remindersPaused = false
    public var startHour = 9
    /// Exclusive end; 24 means midnight. A single daytime window, no overnight wrap.
    public var endHour = 21
    public var intervalHours = 2
    public init() {}
    enum CodingKeys: String, CodingKey { case goalMilliliters, quickAddMilliliters, remindersEnabled, remindersPaused, startHour, endHour, intervalHours }
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        goalMilliliters = try c.decodeIfPresent(Double.self, forKey: .goalMilliliters)
        quickAddMilliliters = try c.decodeIfPresent(Double.self, forKey: .quickAddMilliliters) ?? 250
        remindersEnabled = try c.decodeIfPresent(Bool.self, forKey: .remindersEnabled) ?? false
        remindersPaused = try c.decodeIfPresent(Bool.self, forKey: .remindersPaused) ?? false
        startHour = try c.decodeIfPresent(Int.self, forKey: .startHour) ?? 9
        endHour = try c.decodeIfPresent(Int.self, forKey: .endHour) ?? 21
        intervalHours = try c.decodeIfPresent(Int.self, forKey: .intervalHours) ?? 2
    }
    public func validate() throws {
        guard goalMilliliters.map({ $0.isFinite && MetricKind.water.validRange.contains($0) }) ?? true,
              quickAddMilliliters.isFinite, MetricKind.water.validRange.contains(quickAddMilliliters),
              (0...23).contains(startHour), (1...24).contains(endHour), startHour < endHour,
              (1...4).contains(intervalHours) else { throw TrackerError.invalidEntry }
    }
    public var reminderHours: [Int] {
        guard remindersEnabled, !remindersPaused, (try? validate()) != nil else { return [] }
        return Array(stride(from: startHour, to: endHour, by: intervalHours))
    }
}

public enum WaterHistory {
    /// Calendar boundaries, including short/long DST days; no fixed 24-hour buckets.
    public static func entries(_ metrics: [MetricEntry], on day: Date, calendar: Calendar = .current) -> [MetricEntry] {
        guard let interval = calendar.dateInterval(of: .day, for: day) else { return [] }
        return metrics.filter { $0.kind == .water && $0.date >= interval.start && $0.date < interval.end }
            .sorted { $0.date == $1.date ? $0.id.uuidString < $1.id.uuidString : $0.date > $1.date }
    }
    public static func total(_ metrics: [MetricEntry], on day: Date, calendar: Calendar = .current) -> Double {
        guard let interval = calendar.dateInterval(of: .day, for: day) else { return 0 }
        return metrics.reduce(0) { total, entry in
            total + (entry.kind == .water && entry.date >= interval.start && entry.date < interval.end ? entry.value : 0)
        }
    }
}

public struct WaterDay: Identifiable, Equatable, Sendable {
    public var date: Date
    public var milliliters: Double
    public var entryCount: Int
    public var id: Date { date }
}

public extension WaterHistory {
    /// Includes empty days, explicitly distinguished from a recorded amount of zero.
    static func days(_ metrics: [MetricEntry], ending day: Date, count: Int, calendar: Calendar = .current) -> [WaterDay] {
        guard (1...366).contains(count), let end = calendar.dateInterval(of: .day, for: day)?.end,
              let start = calendar.date(byAdding: .day, value: -count, to: end) else { return [] }
        let grouped = Dictionary(grouping: metrics.filter { $0.kind == .water && $0.date >= start && $0.date < end }, by: { calendar.startOfDay(for: $0.date) })
        return (0..<count).compactMap { offset in
            guard let date = calendar.date(byAdding: .day, value: offset, to: start) else { return nil }
            let entries = grouped[date] ?? []
            return WaterDay(date: date, milliliters: entries.reduce(0) { $0 + $1.value }, entryCount: entries.count)
        }
    }
}
