import Testing
import Foundation
import SevenKit
import AlarmCore
@testable import AlarmUI

@MainActor struct ModelTests {
    func model(expired:Bool = false) -> (AlarmModel,PreviewAlarmDriver) {
        let defaults = UserDefaults(suiteName:"AlarmTest.\(UUID())")!
        let source = LocalSource(defaults:defaults); source.apply(expired ? .expired : .unlocked)
        let store = Store(config:SevenConfig(trialProductID:"test.trial",unlockProductID:"test.unlock"),source:source,defaults:defaults)
        let driver = PreviewAlarmDriver()
        return (AlarmModel(store:store,repository:LoopRepository(file:nil),driver:driver),driver)
    }
    @Test func saveReconcileEditAndRemove() async throws {
        let (m,d) = model(); await m.start(); var r = Routine()
        #expect(await m.save(r)); #expect(d.rows.count == 1)
        r.slots[0].minute = 480; #expect(await m.save(r)); #expect(d.rows.count == 1); #expect(d.rows.values.first?.minute == 480)
        await m.delete(r); #expect(d.rows.isEmpty)
    }
    @Test func failuresStayVisibleAndRetry() async throws {
        let (m,d) = model(); await m.start(); d.failure = "capacity reached"
        #expect(await m.save(Routine())); #expect(m.scheduling.contains("capacity reached")); #expect(d.rows.isEmpty)
        d.failure = nil; await m.synchronize(); #expect(d.rows.count == 1)
    }
    @Test func expiryPreservesArmedAlarms() async throws {
        let (m,d) = model(expired:true); var a = Routine(); a.name = "Existing"
        let saved = a; _ = await m.mutate { $0.routines.append(saved) }; await m.start()
        #expect(d.rows.count == 1); #expect(!(await m.save(Routine())))
        await m.toggle(a,enabled:false); #expect(d.rows.isEmpty)
    }
    @Test func reminderDaysAreSeparateRegistrations() async throws {
        let (m,d) = model(); await m.start(); let r = Routine(kind:.loop)
        #expect(await m.save(r)); #expect(d.rows.count == 5)
        #expect(d.rows.values.allSatisfy {$0.weekdays.count == 1})
    }
}
