import Testing
import Foundation
import SevenKit
import AlarmCore
@testable import AlarmUI

/// Regressions from the 11 November audit: rung one-offs, occurrence-scoped snoozes, wake-up check cleanup.
@MainActor struct AuditFixTests {
    func model() -> (AlarmModel,PreviewAlarmDriver) {
        let defaults = UserDefaults(suiteName:"AlarmAudit.\(UUID())")!
        let source = LocalSource(defaults:defaults); source.apply(.unlocked)
        let store = Store(config:SevenConfig(trialProductID:"test.trial",unlockProductID:"test.unlock"),source:source,defaults:defaults)
        let driver = PreviewAlarmDriver()
        return (AlarmModel(store:store,repository:LoopRepository(file:nil),driver:driver),driver)
    }
    /// A one-off whose time has passed: its routine turns off, but the receipt survives the reconcile
    /// so the Open intent that launched the app can still find it.
    @Test func rungOneOffTurnsOffAndStillOpens() async throws {
        let (m,d) = model(); await m.start()
        var r = Routine(); r.timing = .once; r.once = Date().addingTimeInterval(-30)
        let row = Registration(id:ScheduleCompiler.identifier(r.id,key:"once"),routine:r,date:r.once)
        let routine = r
        _ = await m.mutate { $0.routines.append(routine); $0.installed.append(row) }
        d.rows[row.id] = row
        await m.synchronize()
        #expect(m.archive.routines.first?.enabled == false)
        #expect(m.archive.installed.contains { $0.id == row.id })
        #expect(d.rows[row.id] != nil)
        try await m.openNative(row.id.uuidString)
        #expect(m.mission?.id == row.id)
        #expect(m.mission?.occurrenceID != nil)
    }
    /// Receipts older than the grace period are pruned as before.
    @Test func oldRungReceiptIsPruned() async throws {
        let (m,_) = model(); await m.start()
        var r = Routine(); r.timing = .once; r.once = Date().addingTimeInterval(-7200)
        let row = Registration(id:ScheduleCompiler.identifier(r.id,key:"once"),routine:r,date:r.once)
        let routine = r
        _ = await m.mutate { $0.routines.append(routine); $0.installed.append(row) }
        await m.synchronize()
        #expect(!m.archive.installed.contains { $0.id == row.id })
    }
    /// Two ringings of the same weekly registration snooze under different IDs.
    @Test func snoozesAreScopedToTheirOccurrence() async throws {
        let (m,d) = model(); await m.start()
        #expect(await m.save(Routine()))
        let weekly = try #require(m.archive.installed.first)
        try await m.openNative(weekly.id.uuidString)
        let first = try #require(m.mission)
        #expect(await m.finish(first,kind:.snoozed))
        try await m.openNative(weekly.id.uuidString)
        let second = try #require(m.mission)
        #expect(first.occurrenceID != second.occurrenceID)
        #expect(await m.finish(second,kind:.snoozed))
        let snoozes = m.archive.installed.filter { $0.purpose == "snooze" }
        #expect(snoozes.count == 2); #expect(Set(snoozes.map(\.id)).count == 2)
        #expect(d.rows.values.filter { $0.purpose == "snooze" }.count == 2)
    }
    /// Reopening an already open mission keeps its occurrence, so a relaunch cannot double a snooze.
    @Test func reopeningKeepsTheOccurrence() async throws {
        let (m,_) = model(); await m.start()
        #expect(await m.save(Routine()))
        let weekly = try #require(m.archive.installed.first)
        try await m.openNative(weekly.id.uuidString); let a = m.mission?.occurrenceID
        try await m.openNative(weekly.id.uuidString); let b = m.mission?.occurrenceID
        #expect(a != nil); #expect(a == b)
    }
    /// A wake-up check that already rang has no native alarm; clearing it removes the receipt without failing.
    @Test func clearingChecksSkipsOnesThatAlreadyRang() async throws {
        let (m,d) = model(); d.strictCancel = true; await m.start()
        var r = Routine(); r.followUpMinutes = 5
        #expect(await m.save(r))
        let weekly = try #require(m.archive.installed.first)
        try await m.openNative(weekly.id.uuidString)
        #expect(await m.finish(try #require(m.mission),kind:.completed))
        let check = try #require(m.archive.installed.first { $0.purpose == "followUp" })
        d.rows.removeValue(forKey:check.id)
        await m.clearWakeUpChecks()
        #expect(m.error == nil)
        #expect(!m.archive.installed.contains { $0.purpose == "followUp" })
        #expect(d.rows[weekly.id] != nil)
    }
    /// Emergency stop still works when a snooze it would cancel has already rung.
    @Test func emergencyStopSkipsExtrasThatAlreadyRang() async throws {
        let (m,d) = model(); d.strictCancel = true; await m.start()
        #expect(await m.save(Routine()))
        let weekly = try #require(m.archive.installed.first)
        try await m.openNative(weekly.id.uuidString)
        #expect(await m.finish(try #require(m.mission),kind:.snoozed))
        let snooze = try #require(m.archive.installed.first { $0.purpose == "snooze" })
        d.rows.removeValue(forKey:snooze.id)
        try await m.openNative(snooze.id.uuidString)
        let ringing = try #require(m.mission)
        var other = snooze; other.id = UUID(); other.snoozes = 2
        let stale = other; _ = await m.mutate { $0.installed.append(stale) }
        #expect(await m.finish(ringing,kind:.emergencyStop))
        #expect(m.error == nil)
        #expect(m.archive.events.last?.kind == .emergencyStop)
    }
}
