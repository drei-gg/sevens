import Foundation
import Testing
@testable import FastCore

@Test func oldArchivesKeepWaterLogsWithRemindersOff() throws {
    let now = Date(timeIntervalSince1970: 1_790_000_000)
    var archive = FastArchive(); archive.metrics = [MetricEntry(date: now, kind: .water, value: 250)]
    var json = try #require(JSONSerialization.jsonObject(with: ArchiveCodec.encode(archive)) as? [String: Any])
    var settings = try #require(json["settings"] as? [String: Any]); settings.removeValue(forKey: "water"); json["settings"] = settings
    let restored = try ArchiveCodec.decode(JSONSerialization.data(withJSONObject: json), now: now)
    #expect(restored.settings.water == WaterSettings())
    #expect(restored.settings.water.goalMilliliters == nil)
    #expect(WaterHistory.total(restored.metrics, on: now) == 250)
    #expect(ReminderPlan.events(archive: restored, now: now).isEmpty)
}

@Test func waterRespectsDSTMidnightAndTimeZoneBoundaries() throws {
    var calendar = Calendar(identifier: .gregorian); calendar.timeZone = try #require(TimeZone(identifier: "America/New_York"))
    for (month, day, hours) in [(3, 8, 23), (11, 1, 25)] {
        let date = try #require(calendar.date(from: DateComponents(year: 2026, month: month, day: day, hour: 12)))
        let bounds = try #require(calendar.dateInterval(of: .day, for: date))
        #expect(bounds.duration == Double(hours * 3600))
        let metrics = [MetricEntry(date: bounds.start.addingTimeInterval(-1), kind: .water, value: 500),
                       MetricEntry(date: bounds.start, kind: .water, value: 150),
                       MetricEntry(date: bounds.end.addingTimeInterval(-1), kind: .water, value: 250),
                       MetricEntry(date: bounds.end, kind: .water, value: 500),
                       MetricEntry(date: date, kind: .weight, value: 70)]
        #expect(WaterHistory.total(metrics, on: date, calendar: calendar) == 400)
        let archive: FastArchive = { var a = FastArchive(); a.metrics = metrics; return a }()
        #expect(FastWatchSnapshot(archive: archive, canCreateRecords: true, now: date, calendar: calendar).waterMilliliters == 400)
        var utc = calendar; utc.timeZone = TimeZone(secondsFromGMT: 0)!
        #expect(WaterHistory.total(metrics, on: date, calendar: utc) == 650)
    }
}

@Test func waterRemindersRespectWindowAndNotificationBudget() throws {
    let now = Date(timeIntervalSince1970: 1_790_000_000)
    var archive = FastArchive(); archive.settings.water.remindersEnabled = true
    let water = ReminderPlan.events(archive: archive, now: now)
    #expect(water.map(\.hour) == [9, 11, 13, 15, 17, 19])
    #expect(water.allSatisfy { $0.date == nil && $0.weekday == nil && $0.minute == 0 && $0.kind == .water })
    archive.settings.water.startHour = 0; archive.settings.water.endHour = 24; archive.settings.water.intervalHours = 1
    archive.settings.goalReminder = true; archive.fasts = [FastRecord(start: now)]
    archive.settings.reminderWeekdays = [1, 2, 3, 4, 5, 6, 7]; archive.settings.scheduleReminders = true
    archive.plans = (1...100).map { PlannedFast(start: now.addingTimeInterval(Double($0 * 3600))) }
    let all = ReminderPlan.events(archive: archive, now: now)
    #expect(all.count == 64); #expect(Set(all.map(\.id)).count == 64)
    #expect(all.filter { $0.kind == .water }.count == 24)
    #expect(all.filter { $0.kind == .planned }.last?.date == now.addingTimeInterval(32 * 3600))
    archive.settings.water.remindersEnabled = false
    #expect(ReminderPlan.events(archive: archive, now: now).allSatisfy { $0.kind != .water })
    archive.settings.water.remindersEnabled = true; archive.settings.water.startHour = 21; archive.settings.water.endHour = 9
    #expect(throws: TrackerError.invalidEntry) { try archive.validate(now: now) }
}

@Test func waterPreferencesRoundTripAndMergeKeepsLocalChoices() throws {
    let now = Date(timeIntervalSince1970: 1_790_000_000)
    var archive = FastArchive(); archive.settings.useFluidOunces = true
    archive.settings.water.goalMilliliters = Units.canonical(64, kind: .water, settings: archive.settings)
    archive.settings.water.quickAddMilliliters = Units.canonical(8, kind: .water, settings: archive.settings)
    archive.settings.water.remindersEnabled = true
    archive.metrics = [MetricEntry(date: now, kind: .water, value: archive.settings.water.quickAddMilliliters)]
    let copy = try ArchiveCodec.decode(ArchiveCodec.encode(archive), now: now)
    #expect(copy.settings == archive.settings)
    #expect(abs(Units.display(copy.metrics[0].value, kind: .water, settings: copy.settings) - 8) < 0.000001)
    let merged = try ArchiveCodec.merge(copy, into: FastArchive(), now: now)
    #expect(merged.archive.metrics.count == 1); #expect(!merged.archive.settings.water.remindersEnabled)
    #expect(ArchiveCodec.csv(copy).contains("\"water\""))
    for invalid in [0.0, -1, 10_001, .infinity, .nan] {
        var bad = archive; bad.settings.water.goalMilliliters = invalid
        #expect(throws: TrackerError.invalidEntry) { try bad.validate(now: now) }
    }
}

@Test func waterPausePersistsWithoutErasingReminderWindow() throws {
    let now = Date(timeIntervalSince1970: 1_790_000_000)
    var archive = FastArchive(); archive.settings.water.remindersEnabled = true
    archive.settings.goalReminder = true; archive.fasts = [FastRecord(start: now)]
    archive.settings.water.remindersPaused = true
    let restored = try ArchiveCodec.decode(ArchiveCodec.encode(archive), now: now)
    let events = ReminderPlan.events(archive: restored, now: now)
    #expect(events.count == 1); #expect(events.first?.kind == .goal)
    archive.settings.water.remindersPaused = false
    #expect(ReminderPlan.events(archive: archive, now: now).filter { $0.kind == .water }.count == 6)
}

@Test func waterTrendDaysIncludeMissingLogsAndRespectSelectedPeriod() throws {
    var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let today = try #require(calendar.date(from: DateComponents(year: 2026, month: 11, day: 12, hour: 12)))
    let yesterday = today.addingTimeInterval(-86400)
    let metrics = [MetricEntry(date: today, kind: .water, value: 250), MetricEntry(date: today, kind: .water, value: 150),
                   MetricEntry(date: yesterday, kind: .water, value: 500), MetricEntry(date: today.addingTimeInterval(-8 * 86400), kind: .water, value: 1000)]
    let days = WaterHistory.days(metrics, ending: today, count: 7, calendar: calendar)
    #expect(days.count == 7); #expect(days.last?.milliliters == 400); #expect(days.last?.entryCount == 2)
    #expect(days.reduce(0) { $0 + $1.milliliters } == 900)
    #expect(days.filter { $0.entryCount == 0 }.count == 5)
    #expect(WaterHistory.days(metrics, ending: today, count: 0, calendar: calendar).isEmpty)
}
