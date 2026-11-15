import Testing
import Foundation
import SevenKit
import AlarmCore
@testable import AlarmUI

@MainActor struct ConvenienceTests {
    func model() -> (AlarmModel,PreviewAlarmDriver) {
        let defaults = UserDefaults(suiteName:"AlarmConvenience.\(UUID())")!
        let source = LocalSource(defaults:defaults); source.apply(.unlocked)
        let store = Store(config:SevenConfig(trialProductID:"test.trial",unlockProductID:"test.unlock"),source:source,defaults:defaults)
        let driver = PreviewAlarmDriver()
        return (AlarmModel(store:store,repository:LoopRepository(file:nil),driver:driver),driver)
    }
    @Test func duplicateStartsDisabledAndGroupToggleKeepsSettings() async {
        let (m,d) = model(); await m.start(); var r = Routine(); r.group = "Work"; r.reason = "Catch the train"; r.tone = .chime
        #expect(await m.save(r)); await m.duplicate(r)
        #expect(m.archive.routines.count == 2); #expect(d.rows.count == 1)
        let copy = m.archive.routines.first {$0.id != r.id}!
        #expect(!copy.enabled); #expect(copy.reason == r.reason); #expect(copy.tone == .chime)
        await m.toggleGroup("Work",enabled:false); #expect(d.rows.isEmpty)
        await m.toggleGroup("Work",enabled:true); #expect(d.rows.count == 2)
    }
    @Test func napIsFutureOneOffAndChecksCancelIndependently() async throws {
        let (m,d) = model(); await m.start(); let start = Date(); await m.nap(minutes:20)
        let r = try #require(m.archive.routines.first)
        #expect(r.timing == .once); #expect(abs(r.once.timeIntervalSince(start)-1200) < 5)
        let row = try #require(d.rows.values.first)
        try await m.extra(row,minutes:5,purpose:"followUp"); #expect(d.rows.count == 2)
        await m.clearWakeUpChecks(); #expect(d.rows.count == 1); #expect(m.archive.routines[0].enabled)
        #expect(d.rows[row.id] != nil)
    }
    @Test func defaultsPersistWithoutReschedulingExistingAlarms() async throws {
        let (m,d) = model(); await m.start(); let original = Routine()
        #expect(await m.save(original)); let installed = d.rows
        var defaults = AlarmDefaults(); defaults.tone = .bright; defaults.snoozeMinutes = 12; defaults.snoozeLimit = 0; defaults.followUpMinutes = 7
        #expect(await m.saveDefaults(defaults)); await m.reload()
        #expect(m.archive.alarmDefaults == defaults)
        #expect(m.archive.routines == [original]); #expect(d.rows == installed)
        let fresh = m.newRoutine(); #expect(fresh.tone == .bright); #expect(fresh.snoozeLimit == 0)
        #expect(await m.nap(minutes:37))
        let nap = try #require(m.archive.routines.first { $0.timing == .once })
        #expect(nap.snoozeMinutes == 12); #expect(nap.followUpMinutes == 7)
        #expect(!((await m.nap(minutes:0)))); #expect(!((await m.nap(minutes:721))))
        #expect(m.archive.routines.count == 2)
        var invalid = defaults; invalid.snoozeMinutes = 31
        #expect(!(await m.saveDefaults(invalid))); #expect(m.archive.alarmDefaults == defaults)
    }
    @Test func diagnosticsReportMissingRegistrationsWithoutPrivateText() async throws {
        let (m,d) = model(); await m.start(); var r = Routine()
        r.name = "PRIVATE_NAME"; r.reason = "PRIVATE_REASON"; r.group = "PRIVATE_GROUP"
        r.code = "PRIVATE_CODE"; r.phrase = "PRIVATE_PHRASE"
        #expect(await m.save(r)); d.rows.removeAll()
        m.error = "PRIVATE_ERROR"; m.scheduling = "PRIVATE_SCHEDULE_ERROR"
        let report = await m.diagnostics()
        #expect(report.desiredRegistrationCount == 1); #expect(report.nativeRegistrationCount == 0)
        #expect(report.missingRegistrationCount == 1); #expect(report.hasLocalError)
        #expect(report.nativeQuerySucceeded && report.scheduleValidationSucceeded)
        let data = try await m.diagnosticData()
        let text = try #require(String(data:data,encoding:.utf8))
        #expect(!text.contains("PRIVATE_")); #expect(!text.contains(r.id.uuidString))
    }
}
