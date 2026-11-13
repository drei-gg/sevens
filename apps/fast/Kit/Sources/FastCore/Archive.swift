import Foundation

public enum TrackerError: Error, Equatable, Sendable {
    case invalidTime, futureRecord, invalidGoal, overlap, multipleActive, duplicateID
    case invalidEntry, danglingJournal, unsupportedSchema, unreadable, conflict, tooLarge, readOnly
}

public struct FastArchive: Codable, Equatable, Sendable {
    public var schemaVersion = 1
    public var fasts: [FastRecord] = []
    public var journal: [JournalEntry] = []
    public var metrics: [MetricEntry] = []
    public var plans: [PlannedFast] = []
    public var restDays: [RestDay] = []
    public var presets: [FastPreset] = []
    public var settings = TrackerSettings()
    public var companionRevision = UUID()
    public var companionReceipts: [FastWatchReceipt] = []
    public init() {}
    enum CodingKeys: String, CodingKey {
        case schemaVersion, fasts, journal, metrics, plans, restDays, presets, settings, companionRevision, companionReceipts
    }
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try c.decode(Int.self, forKey: .schemaVersion)
        fasts = try c.decode([FastRecord].self, forKey: .fasts)
        journal = try c.decode([JournalEntry].self, forKey: .journal)
        metrics = try c.decode([MetricEntry].self, forKey: .metrics)
        plans = try c.decode([PlannedFast].self, forKey: .plans)
        restDays = try c.decode([RestDay].self, forKey: .restDays)
        presets = try c.decode([FastPreset].self, forKey: .presets)
        settings = try c.decode(TrackerSettings.self, forKey: .settings)
        companionRevision = try c.decodeIfPresent(UUID.self, forKey: .companionRevision) ?? UUID()
        companionReceipts = try c.decodeIfPresent([FastWatchReceipt].self, forKey: .companionReceipts) ?? []
    }
    public var active: FastRecord? { fasts.first { $0.end == nil } }
    public var latestCompleted: FastRecord? { fasts.filter { $0.end != nil }.max { $0.end! < $1.end! } }

    /// Validation is shared by UI mutations, disk reads and imports. No repair by silently dropping data.
    public func validate(now: Date) throws {
        guard companionReceipts.count <= 2_000 else { throw TrackerError.tooLarge }
        try unique(companionReceipts.map { $0.request.id })
        guard companionReceipts.allSatisfy({ validDate($0.savedAt) && validDate($0.request.sentAt) }) else { throw TrackerError.invalidEntry }
        guard schemaVersion == 1 else { throw TrackerError.unsupportedSchema }
        guard fasts.count + journal.count + metrics.count + plans.count + restDays.count <= 100_000 else {
            throw TrackerError.tooLarge
        }
        try unique(fasts.map(\.id)); try unique(journal.map(\.id)); try unique(metrics.map(\.id))
        try unique(plans.map(\.id)); try unique(restDays.map(\.id)); try unique(presets.map(\.id))
        guard fasts.filter({ $0.end == nil }).count <= 1 else { throw TrackerError.multipleActive }
        let sorted = fasts.sorted { $0.start < $1.start }
        for (index, fast) in sorted.enumerated() {
            guard validDate(fast.start), fast.end.map(validDate) ?? true,
                  fast.end.map({ $0 > fast.start }) ?? true else { throw TrackerError.invalidTime }
            guard fast.start <= now, fast.end.map({ $0 <= now }) ?? true else { throw TrackerError.futureRecord }
            try validGoal(fast.goalMinutes, eating: fast.eatingMinutes)
            guard TimeZone(identifier: fast.timeZoneID) != nil,
                  fast.intention.count <= 10_000, fast.reflection.count <= 10_000 else { throw TrackerError.invalidEntry }
            try validTags(fast.tags)
            if index > 0, (sorted[index - 1].end ?? .distantFuture) > fast.start { throw TrackerError.overlap }
        }
        let ids = Set(fasts.map(\.id))
        for entry in journal {
            guard validDate(entry.date), entry.date <= now, entry.text.count <= 20_000,
                  [entry.mood, entry.energy, entry.hunger].allSatisfy({ $0.map { (1...5).contains($0) } ?? true }) else {
                throw TrackerError.invalidEntry
            }
            if let fastID = entry.fastID, !ids.contains(fastID) { throw TrackerError.danglingJournal }
            try validTags(entry.tags)
        }
        for metric in metrics {
            guard validDate(metric.date), metric.date <= now, metric.value.isFinite,
                  metric.kind.validRange.contains(metric.value), (metric.externalID?.count ?? 0) < 256 else {
                throw TrackerError.invalidEntry
            }
        }
        let externalIDs = metrics.compactMap(\.externalID)
        guard Set(externalIDs).count == externalIDs.count else { throw TrackerError.duplicateID }
        for plan in plans {
            guard validDate(plan.start), plan.intention.count <= 10_000 else { throw TrackerError.invalidEntry }
            try validGoal(plan.goalMinutes, eating: plan.eatingMinutes)
        }
        for day in restDays {
            guard validDate(day.date), day.note.count <= 10_000 else { throw TrackerError.invalidEntry }
        }
        guard presets.count <= 100 else { throw TrackerError.tooLarge }
        for preset in presets {
            guard !preset.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, preset.name.count <= 80 else {
                throw TrackerError.invalidEntry
            }
            try validGoal(preset.goalMinutes, eating: preset.eatingMinutes)
        }
        try settings.water.validate()
        guard (0...23).contains(settings.reminderHour), (0...59).contains(settings.reminderMinute),
              settings.reminderWeekdays.allSatisfy({ (1...7).contains($0) }),
              Set(settings.reminderWeekdays).count == settings.reminderWeekdays.count else { throw TrackerError.invalidEntry }
    }

    public mutating func save(_ fast: FastRecord, now: Date) throws {
        var next = self
        next.fasts.removeAll { $0.id == fast.id }; next.fasts.append(fast)
        try next.validate(now: now); self = next
    }
    public mutating func deleteFast(_ id: UUID) {
        fasts.removeAll { $0.id == id }
        for index in journal.indices where journal[index].fastID == id { journal[index].fastID = nil }
    }

    private func unique(_ ids: [UUID]) throws {
        guard Set(ids).count == ids.count else { throw TrackerError.duplicateID }
    }
    private func validDate(_ date: Date) -> Bool {
        date.timeIntervalSince1970.isFinite && date >= Date(timeIntervalSince1970: -2_208_988_800)
            && date < Date(timeIntervalSince1970: 7_258_118_400)
    }
    private func validGoal(_ goal: Int?, eating: Int) throws {
        // Recording limits are input protection, not recommended fasting durations.
        guard goal.map({ (1...525_600).contains($0) }) ?? true, (1...10_080).contains(eating) else {
            throw TrackerError.invalidGoal
        }
    }
    private func validTags(_ tags: [String]) throws {
        guard tags.count <= 20, tags.allSatisfy({ !$0.isEmpty && $0.count <= 80 }) else { throw TrackerError.invalidEntry }
    }
}
