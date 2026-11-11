import Foundation

public struct ImportPreview: Equatable, Sendable {
    public var archive: FastArchive
    public var addedFasts: Int
    public var addedJournal: Int
    public var addedMetrics: Int
    public var addedPlans: Int
    public var addedRestDays: Int
    public var addedPresets: Int
    public var totalAdded: Int { addedFasts + addedJournal + addedMetrics + addedPlans + addedRestDays + addedPresets }
}

public enum ArchiveCodec {
    public static let maxBytes = 25 * 1_024 * 1_024
    public static func encode(_ archive: FastArchive) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(archive)
    }
    public static func decode(_ data: Data, now: Date) throws -> FastArchive {
        guard data.count <= maxBytes else { throw TrackerError.tooLarge }
        struct Header: Decodable { var schemaVersion: Int }
        guard let header = try? JSONDecoder().decode(Header.self, from: data) else { throw TrackerError.unreadable }
        guard header.schemaVersion == 1 else { throw TrackerError.unsupportedSchema }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .millisecondsSince1970
        guard let archive = try? decoder.decode(FastArchive.self, from: data) else { throw TrackerError.unreadable }
        try archive.validate(now: now)
        return archive
    }
    /// Existing settings win; restore is a merge, never a silent replacement of live tracking.
    public static func merge(_ incoming: FastArchive, into current: FastArchive, now: Date) throws -> ImportPreview {
        try incoming.validate(now: now); try current.validate(now: now)
        var next = current
        next.fasts = try merged(current.fasts, incoming.fasts, dates: [\.start], optionalDates: [\.end])
        next.journal = try merged(current.journal, incoming.journal, dates: [\.date])
        next.metrics = try merged(current.metrics, incoming.metrics, dates: [\.date])
        next.plans = try merged(current.plans, incoming.plans, dates: [\.start])
        next.restDays = try merged(current.restDays, incoming.restDays, dates: [\.date])
        next.presets = try merged(current.presets, incoming.presets)
        try next.validate(now: now)
        return ImportPreview(archive: next, addedFasts: next.fasts.count - current.fasts.count,
            addedJournal: next.journal.count - current.journal.count, addedMetrics: next.metrics.count - current.metrics.count,
            addedPlans: next.plans.count - current.plans.count, addedRestDays: next.restDays.count - current.restDays.count,
            addedPresets: next.presets.count - current.presets.count)
    }
    private static func merged<T: Identifiable & Equatable>(_ existing: [T], _ incoming: [T],
        dates: [WritableKeyPath<T, Date>] = [], optionalDates: [WritableKeyPath<T, Date?>] = []) throws -> [T] where T.ID == UUID {
        let known = Dictionary(uniqueKeysWithValues: existing.map { ($0.id, $0) })
        for item in incoming {
            if var old = known[item.id] {
                // Converting Date's reference epoch to JSON milliseconds and back can lose
                // sub-microsecond precision. Ignore only that noise; preserve the local record
                // and compare every non-date field exactly. This is not last-write-wins.
                for key in dates where abs(old[keyPath: key].timeIntervalSince(item[keyPath: key])) <= 0.000_001 {
                    old[keyPath: key] = item[keyPath: key]
                }
                for key in optionalDates {
                    if let left = old[keyPath: key], let right = item[keyPath: key], abs(left.timeIntervalSince(right)) <= 0.000_001 {
                        old[keyPath: key] = right
                    }
                }
                guard old == item else { throw TrackerError.conflict }
            }
        }
        return existing + incoming.filter { known[$0.id] == nil }
    }

    /// One typed table, so no metric or journal entry is left behind in a fasting-only export.
    public static func csv(_ archive: FastArchive) -> String {
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        func date(_ value: Date) -> String { formatter.string(from: value) }
        func number(_ value: Int?) -> String { value.map(String.init) ?? "" }
        var rows = [["type", "id", "start", "end", "goal_minutes", "eating_minutes", "timezone", "fast_id",
                     "kind", "value", "unit", "intention", "reflection_or_text", "tags", "mood", "energy", "hunger", "external_id"]]
        for fast in archive.fasts.sorted(by: { $0.start < $1.start }) {
            rows.append(["fast", fast.id.uuidString, date(fast.start), fast.end.map(date) ?? "", number(fast.goalMinutes),
                         String(fast.eatingMinutes), fast.timeZoneID, "", "", "", "", fast.intention, fast.reflection,
                         fast.tags.joined(separator: ";"), "", "", "", ""])
        }
        for entry in archive.journal.sorted(by: { $0.date < $1.date }) {
            rows.append(["journal", entry.id.uuidString, date(entry.date), "", "", "", "", entry.fastID?.uuidString ?? "",
                         "", "", "", "", entry.text, entry.tags.joined(separator: ";"), number(entry.mood),
                         number(entry.energy), number(entry.hunger), ""])
        }
        for metric in archive.metrics.sorted(by: { $0.date < $1.date }) {
            rows.append(["metric", metric.id.uuidString, date(metric.date), "", "", "", "", "", metric.kind.rawValue,
                         String(metric.value), metric.kind.canonicalUnit, "", "", "", "", "", "", metric.externalID ?? ""])
        }
        for plan in archive.plans {
            rows.append(["plan", plan.id.uuidString, date(plan.start), "", number(plan.goalMinutes), String(plan.eatingMinutes),
                         "", "", "", "", "", plan.intention, "", "", "", "", "", ""])
        }
        for day in archive.restDays {
            rows.append(["rest", day.id.uuidString, date(day.date), "", "", "", "", "", "", "", "", "", day.note, "", "", "", "", ""])
        }
        for preset in archive.presets {
            rows.append(["preset", preset.id.uuidString, "", "", number(preset.goalMinutes), String(preset.eatingMinutes),
                         "", "", "", "", "", preset.name, "", "", "", "", "", ""])
        }
        return rows.map { $0.map(csvCell).joined(separator: ",") }.joined(separator: "\r\n") + "\r\n"
    }
    private static func csvCell(_ raw: String) -> String {
        // Spreadsheet formula injection protection. JSON backup preserves the original exactly.
        let first = raw.trimmingCharacters(in: .whitespacesAndNewlines).first
        let protected = first.map { "=+-@".contains($0) } == true ? "'" + raw : raw
        return "\"" + protected.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
}
