import Foundation
import Testing
@testable import FastCore

private func instant(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }
private let now = instant("2026-11-11T12:00:00Z")
private func calendar(_ zone: String = "UTC") -> Calendar {
    var value = Calendar(identifier: .gregorian); value.timeZone = TimeZone(identifier: zone)!; value.firstWeekday = 2
    return value
}

@Test func lifecycleAndMultipleSessionsPerDay() throws {
    var archive = FastArchive()
    var first = FastRecord(start: now.addingTimeInterval(-36_000), goalMinutes: 60)
    try archive.save(first, now: now)
    #expect(archive.active?.id == first.id)
    first.end = first.start.addingTimeInterval(7_200)
    try archive.save(first, now: now)
    #expect(archive.active == nil)
    #expect(archive.fasts.first?.metGoal == true)
    let second = FastRecord(start: first.end!, end: now, goalMinutes: nil)
    try archive.save(second, now: now)
    #expect(archive.fasts.count == 2)
    #expect(second.metGoal == nil)
}

@Test func invalidEditsLeaveArchiveUnchanged() throws {
    var archive = FastArchive()
    let first = FastRecord(start: now.addingTimeInterval(-3_600), end: now)
    try archive.save(first, now: now)
    let before = archive
    #expect(throws: TrackerError.overlap) {
        try archive.save(FastRecord(start: now.addingTimeInterval(-1_800), end: now), now: now)
    }
    #expect(archive == before)
    #expect(throws: TrackerError.futureRecord) { try archive.save(FastRecord(start: now.addingTimeInterval(1)), now: now) }
    #expect(throws: TrackerError.invalidTime) { try archive.save(FastRecord(start: now, end: now), now: now) }
}

@Test func activeSessionsCannotOverlapEvenWithHistoricalInsertion() throws {
    var archive = FastArchive()
    try archive.save(FastRecord(start: now.addingTimeInterval(-3_600)), now: now)
    #expect(throws: TrackerError.multipleActive) { try archive.save(FastRecord(start: now), now: now) }
    #expect(throws: TrackerError.overlap) {
        try archive.save(FastRecord(start: now.addingTimeInterval(-7_200), end: now), now: now)
    }
}

@Test func dailyTotalsSplitMidnightWithoutDuplicatingSessionCount() throws {
    var archive = FastArchive()
    try archive.save(FastRecord(start: instant("2026-11-09T20:00:00Z"), end: instant("2026-11-10T12:00:00Z")), now: now)
    let monday = HistoryMath.day(instant("2026-11-09T00:00:00Z"), archive: archive, calendar: calendar(), now: now)
    let tuesday = HistoryMath.day(instant("2026-11-10T00:00:00Z"), archive: archive, calendar: calendar(), now: now)
    #expect(monday.seconds == 4 * 3_600)
    #expect(tuesday.seconds == 12 * 3_600)
    let interval = DateInterval(start: monday.date, end: instant("2026-11-11T00:00:00Z"))
    let summary = HistoryMath.summary(in: interval, archive: archive, now: now)
    #expect(summary.completedCount == 1)
    #expect(summary.seconds == 16 * 3_600)
    #expect(summary.averageDuration == 57_600.0)
}

@Test(arguments: [("2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", 23.0),
                  ("2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", 25.0)])
func daylightSavingDaysUseActualElapsedHours(start: String, end: String, hours: Double) throws {
    var archive = FastArchive()
    try archive.save(FastRecord(start: instant(start), end: instant(end)), now: now)
    let result = HistoryMath.day(instant(start), archive: archive, calendar: calendar("America/New_York"), now: now)
    #expect(result.seconds == hours * 3_600)
    let following = HistoryMath.day(instant(end), archive: archive, calendar: calendar("America/New_York"), now: now)
    #expect(following.seconds == 0)
}

@Test func monthGridIncludesLeapDayAndMatchesFirstWeekday() {
    let grid = HistoryMath.monthGrid(containing: instant("2024-02-15T00:00:00Z"), calendar: calendar())
    #expect(grid.count == 35)
    #expect(calendar().component(.weekday, from: grid.first!) == 2)
    #expect(grid.contains(instant("2024-02-29T00:00:00Z")))
}

@Test func deletionPreservesJournalAndDetachesLink() throws {
    var archive = FastArchive(); let fast = FastRecord(start: now)
    try archive.save(fast, now: now)
    archive.journal = [JournalEntry(date: now, fastID: fast.id, text: "Noticed a change.")]
    archive.deleteFast(fast.id)
    #expect(archive.journal.count == 1); #expect(archive.journal[0].fastID == nil)
    try archive.validate(now: now)
}

@Test func backupRoundTripPreservesMillisecondsAndAllKinds() throws {
    var archive = FastArchive()
    let fast = FastRecord(start: now.addingTimeInterval(-0.123))
    archive.fasts = [fast]
    archive.journal = [JournalEntry(date: now, fastID: fast.id, text: "Hello\nworld", mood: 3, energy: 2, hunger: 4)]
    archive.metrics = [MetricEntry(date: now, kind: .weight, value: 72.3), MetricEntry(date: now, kind: .water, value: 250)]
    archive.plans = [PlannedFast(start: now.addingTimeInterval(86_400))]
    archive.restDays = [RestDay(date: now)]
    archive.presets = [FastPreset(name: "My rhythm", goalMinutes: 780, eatingMinutes: 660)]
    let decoded = try ArchiveCodec.decode(ArchiveCodec.encode(archive), now: now)
    #expect(abs(decoded.fasts[0].start.timeIntervalSince(fast.start)) < 0.001)
    #expect(decoded.journal == archive.journal)
    #expect(decoded.metrics == archive.metrics)
    #expect(decoded.plans == archive.plans)
    #expect(decoded.presets == archive.presets)
}

@Test func importIsIdempotentRejectsConflictAndOverlap() throws {
    var archive = FastArchive()
    archive.fasts = [FastRecord(start: now.addingTimeInterval(-3_600), end: now)]
    #expect(try ArchiveCodec.merge(archive, into: archive, now: now).totalAdded == 0)
    var conflict = archive; conflict.fasts[0].reflection = "Different"
    #expect(throws: TrackerError.conflict) { try ArchiveCodec.merge(conflict, into: archive, now: now) }
    var overlap = archive; overlap.fasts[0].id = UUID()
    #expect(throws: TrackerError.overlap) { try ArchiveCodec.merge(overlap, into: archive, now: now) }
}

@Test func malformedAndFutureFilesAreNotSilentlyEmptied() {
    #expect(throws: TrackerError.unreadable) { try ArchiveCodec.decode(Data("broken".utf8), now: now) }
    #expect(throws: TrackerError.unsupportedSchema) { try ArchiveCodec.decode(Data("{\"schemaVersion\":2}".utf8), now: now) }
}

@Test func invalidLinksRatingsAndMetricsFailValidation() {
    var archive = FastArchive(); archive.journal = [JournalEntry(date: now, fastID: UUID())]
    #expect(throws: TrackerError.danglingJournal) { try archive.validate(now: now) }
    archive.journal = [JournalEntry(date: now, mood: 6)]
    #expect(throws: TrackerError.invalidEntry) { try archive.validate(now: now) }
    archive.journal = []; archive.metrics = [MetricEntry(date: now, kind: .water, value: -1)]
    #expect(throws: TrackerError.invalidEntry) { try archive.validate(now: now) }
}

@Test func csvIncludesAllRecordsQuotesAndNeutralizesFormulas() {
    var archive = FastArchive()
    archive.journal = [JournalEntry(date: now, text: "=HYPERLINK(\"bad\")\nsecond, line")]
    archive.metrics = [MetricEntry(date: now, kind: .weight, value: 70)]
    let csv = ArchiveCodec.csv(archive)
    #expect(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\nsecond, line\""))
    #expect(csv.contains("\"kg\""))
    #expect(csv.hasSuffix("\r\n"))
}

@Test func unitsRoundTrip() {
    var settings = TrackerSettings(); settings.usePounds = true; settings.useFluidOunces = true
    for kind in MetricKind.allCases {
        let result = Units.canonical(Units.display(70, kind: kind, settings: settings), kind: kind, settings: settings)
        #expect(abs(result - 70) < 0.000001)
    }
}

@Test func failedWriteDoesNotPublishAndCanBeRetried() async throws {
    struct DiskFull: Error {}
    let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let store = ArchiveStore(file: file, writer: { _, _ in throw DiskFull() })
    await #expect(throws: DiskFull.self) {
        try await store.transact(now: now) { $0.fasts = [FastRecord(start: now)] }
    }
    #expect(try await store.read(now: now).fasts.isEmpty)
}

@Test func diskSurvivesNewStoreAndCorruptionBlocksMutation() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let file = directory.appendingPathComponent("records.json")
    let store = ArchiveStore(file: file)
    let record = FastRecord(start: now.addingTimeInterval(-3_600))
    _ = try await store.transact(now: now) { $0.fasts = [record] }
    #expect(try await ArchiveStore(file: file).read(now: now).active == record)
    let broken = Data("broken".utf8); try broken.write(to: file)
    let damaged = ArchiveStore(file: file)
    await #expect(throws: TrackerError.unreadable) { try await damaged.read(now: now) }
    await #expect(throws: TrackerError.unreadable) { try await damaged.transact(now: now) { $0.fasts = [] } }
    #expect(try Data(contentsOf: file) == broken)
}

@Test func goalReachedDoesNotEndActiveRecordAndEarlyFinishIsValid() throws {
    let record = FastRecord(start: now.addingTimeInterval(-7_200), goalMinutes: 60)
    #expect(record.end == nil)
    #expect(record.duration(at: now) == 7_200)
    var archive = FastArchive()
    try archive.save(FastRecord(start: now.addingTimeInterval(-3_600), end: now, goalMinutes: 960), now: now)
    #expect(archive.fasts[0].metGoal == false)
}

@Test func duplicateExternalMeasurementsRejectAnImportWithoutPartialChanges() throws {
    var archive = FastArchive()
    archive.metrics = [MetricEntry(date: now, kind: .weight, value: 70, externalID: "health.sample")]
    var incoming = FastArchive()
    incoming.metrics = [MetricEntry(date: now, kind: .weight, value: 70, externalID: "health.sample")]
    incoming.journal = [JournalEntry(date: now, text: "Must not get imported on failure")]
    #expect(throws: TrackerError.duplicateID) { try ArchiveCodec.merge(incoming, into: archive, now: now) }
    #expect(archive.journal.isEmpty)
}

@Test func olderVersionOneSettingsGetNewDefaults() throws {
    let json = Data("{\"usePounds\":true}".utf8)
    let settings = try JSONDecoder().decode(TrackerSettings.self, from: json)
    #expect(settings.usePounds)
    #expect(!settings.liveActivity)
    #expect(settings.showEatingWindow)
    #expect(settings.reminderWeekdays.isEmpty)
}

@Test func widgetSnapshotContainsNoPersonalNotesOrMeasurements() throws {
    var archive = FastArchive()
    archive.fasts = [FastRecord(start: now, intention: "Private intention", reflection: "Private reflection", tags: ["private tag"])]
    archive.journal = [JournalEntry(date: now, text: "Private journal")]
    archive.metrics = [MetricEntry(date: now, kind: .weight, value: 70)]
    let snapshot = TimerSnapshot(archive: archive)
    let encoded = String(decoding: try JSONEncoder().encode(snapshot), as: UTF8.self)
    #expect(!encoded.contains("Private"))
    #expect(!encoded.contains("weight"))
    #expect(snapshot.start == now)
    archive.fasts[0].end = now.addingTimeInterval(3_600)
    #expect(TimerSnapshot(archive: archive).fastID == nil)
}

@Test func readingAndEditingDoesNotShiftTimezonesOrOriginalGoals() throws {
    var archive = FastArchive()
    var fast = FastRecord(start: instant("2026-11-09T20:00:00Z"), end: instant("2026-11-10T12:00:00Z"), goalMinutes: 840,
                          timeZoneID: "Asia/Manila")
    try archive.save(fast, now: now)
    let originalTotal = HistoryMath.days(in: DateInterval(start: instant("2026-11-09T00:00:00Z"), end: now), archive: archive,
                                         calendar: calendar("Asia/Manila"), now: now).reduce(0) { $0 + $1.seconds }
    let traveledTotal = HistoryMath.days(in: DateInterval(start: instant("2026-11-09T00:00:00Z"), end: now), archive: archive,
                                         calendar: calendar("America/New_York"), now: now).reduce(0) { $0 + $1.seconds }
    #expect(originalTotal == traveledTotal)
    fast.reflection = "A corrected note"
    try archive.save(fast, now: now)
    #expect(archive.fasts[0].timeZoneID == "Asia/Manila")
    #expect(archive.fasts[0].goalMinutes == 840)
}
