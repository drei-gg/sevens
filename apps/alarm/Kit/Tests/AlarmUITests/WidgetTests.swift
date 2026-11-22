import Testing
import Foundation
import SevenKit
import AlarmCore
@testable import AlarmUI

@MainActor struct WidgetTests {
    @Test func persistedScheduleFailureRetryEditAndRemoval() async throws {
        let (model, driver) = ModelTests().model()
        await model.start()
        #expect(model.widgetSnapshot?.items.isEmpty == true)
        var routine = Routine(); routine.name = "Widget study"; routine.timing = .once
        driver.failure = "capacity"
        #expect(await model.save(routine))
        #expect(model.widgetSnapshot?.needsAttention == true)
        #expect(model.widgetSnapshot?.items.first?.registered == false)
        driver.failure = nil; await model.synchronize()
        #expect(model.widgetSnapshot?.items.first?.registered == true)
        #expect(model.widgetSnapshot?.needsAttention == false)
        routine.once = routine.once.addingTimeInterval(900)
        driver.failure = "replace failure"
        #expect(await model.save(routine))
        #expect(model.widgetSnapshot?.items.first?.registered == false)
        #expect(model.widgetSnapshot?.items.first?.date == routine.once)
        driver.failure = nil; await model.synchronize()
        await model.toggle(routine, enabled: false)
        #expect(model.widgetSnapshot?.items.isEmpty == true)
        #expect(driver.rows.isEmpty)
    }
    @Test func failedPersistenceNeverPublishesUnsavedWidgetData() async throws {
        let (model, _) = ModelTests().model(); await model.start()
        let before = model.widgetSnapshot
        #expect(!(await model.mutate { $0.version = 999 }))
        #expect(model.widgetSnapshot == before)
    }
    @Test func anUnprojectableScheduleClearsThePreviouslyCheckedSnapshot() async throws {
        let (model, _) = ModelTests().model(); await model.start()
        #expect(model.widgetSnapshot != nil)
        var routine = Routine(kind: .loop)
        routine.slots = (0..<10).map { TimeSlot(minute: Int32(420 + $0 * 5), weekdays: [1,2,3,4,5,6,7]) }
        let saved = routine
        #expect(await model.mutate { $0.routines.append(saved) })
        #expect(model.widgetSnapshot == nil)
        #expect(model.widgetError != nil)
    }
    @Test func widgetFileRoundTripAndMalformedDataAreIsolatedFromArchive() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("alarm-widget-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: file) }
        let snapshot = try alarmWidgetSnapshot(archive: LoopArchive(), verifiedIds: [], now: Date(timeIntervalSince1970: 1_800_000_000), calendar: CalendarSpec(.current))
        try AlarmWidgetStore.write(snapshot, to: file)
        #expect(AlarmWidgetStore.read(from: file) == snapshot)
        try Data(repeating: 32, count: 65_537).write(to: file)
        #expect(AlarmWidgetStore.read(from: file) == nil)
        try Data("{\"version\":2}".utf8).write(to: file)
        #expect(AlarmWidgetStore.read(from: file) == nil)
        try AlarmWidgetStore.write(nil, to: file)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }
}
