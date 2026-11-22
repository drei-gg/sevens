import Testing
import Foundation
import SevenKit
import AlarmCore
@testable import AlarmUI

/// Progress inside a mission survives leaving and reopening it, belongs to that ringing only, and
/// ends with the mission (the core's `progress.rs`, through the model).
@MainActor struct MissionProgressTests {
    func model(file: URL? = nil) -> (AlarmModel,PreviewAlarmDriver) {
        let defaults = UserDefaults(suiteName:"AlarmProgress.\(UUID())")!
        let source = LocalSource(defaults:defaults); source.apply(.unlocked)
        let store = Store(config:SevenConfig(trialProductID:"test.trial",unlockProductID:"test.unlock"),source:source,defaults:defaults)
        let driver = PreviewAlarmDriver()
        return (AlarmModel(store:store,repository:LoopRepository(file:file),driver:driver),driver)
    }
    func arithmetic(_ name: String = "Wake up", count: Int32 = 3) -> Routine {
        var r = Routine(); r.name = name; r.mission = .arithmetic; r.mathCount = count; return r
    }

    @Test func reopeningResumesMidWayAfterARelaunch() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("progress-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at:file) }
        let (m,_) = model(file:file); await m.start()
        #expect(await m.save(arithmetic()))
        let weekly = try #require(m.archive.installed.first)
        try await m.openNative(weekly.id.uuidString)
        let row = try #require(m.mission)
        var progress = m.progress ?? missionProgressStart(row:row); progress.solved = 2
        await m.saveProgress(progress)
        // The app is closed and launched again: the mission comes back with its progress.
        let (again,_) = model(file:file); await again.start()
        #expect(again.mission?.id == row.id)
        #expect(again.progress?.solved == 2)
        // Pressing Open again on the same ringing keeps it.
        try await again.openNative(weekly.id.uuidString)
        #expect(again.progress?.solved == 2)
    }

    @Test func aDifferentAlarmDoesNotInheritProgress() async throws {
        let (m,_) = model(); await m.start()
        var second = arithmetic("Medication"); second.slots = [TimeSlot(minute:540,weekdays:[2])]
        #expect(await m.save(arithmetic())); #expect(await m.save(second))
        let rows = m.archive.installed
        let first = try #require(rows.first { $0.routine.name == "Wake up" })
        let other = try #require(rows.first { $0.routine.name == "Medication" })
        try await m.openNative(first.id.uuidString)
        var progress = missionProgressStart(row:try #require(m.mission)); progress.solved = 2
        await m.saveProgress(progress)
        try await m.openNative(other.id.uuidString)
        #expect(m.progress == nil)
        #expect(m.archive.missionProgress == nil)
        // A late save for the superseded mission is ignored.
        await m.saveProgress(progress)
        #expect(m.archive.missionProgress == nil)
    }

    @Test func completingClearsAndSnoozingKeepsForTheSnooze() async throws {
        let (m,_) = model(); await m.start()
        #expect(await m.save(arithmetic()))
        let weekly = try #require(m.archive.installed.first)
        try await m.openNative(weekly.id.uuidString)
        var progress = missionProgressStart(row:try #require(m.mission)); progress.solved = 1
        await m.saveProgress(progress)
        #expect(await m.finish(try #require(m.mission),kind:.snoozed))
        let snooze = try #require(m.archive.installed.first { $0.purpose == "snooze" })
        try await m.openNative(snooze.id.uuidString)
        #expect(m.progress?.solved == 1)
        #expect(await m.finish(try #require(m.mission),kind:.completed))
        #expect(m.archive.missionProgress == nil)
        try await m.openNative(weekly.id.uuidString)
        #expect(m.progress == nil)
    }

    @Test func emergencyStopAndSystemStopClearIt() async throws {
        let (m,_) = model(); await m.start()
        #expect(await m.save(arithmetic()))
        let weekly = try #require(m.archive.installed.first)
        try await m.openNative(weekly.id.uuidString)
        await m.saveProgress(MissionProgress(occurrenceId:try #require(m.mission?.occurrenceID),mission:.arithmetic,solved:2,tiles:[],matched:false))
        #expect(await m.finish(try #require(m.mission),kind:.emergencyStop))
        #expect(m.archive.missionProgress == nil)
        try await m.openNative(weekly.id.uuidString)
        await m.saveProgress(MissionProgress(occurrenceId:try #require(m.mission?.occurrenceID),mission:.arithmetic,solved:1,tiles:[],matched:false))
        #expect(m.progress?.solved == 1)
        try await m.dismissNative(weekly.id.uuidString)
        #expect(m.archive.pending == nil); #expect(m.archive.missionProgress == nil)
    }
}
