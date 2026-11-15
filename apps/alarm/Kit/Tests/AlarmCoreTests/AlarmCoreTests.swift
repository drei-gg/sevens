import Foundation
import Testing
@testable import AlarmCore

/// The rules are the Rust core's and are tested there (apps/alarm/core/tests/swift_parity.rs,
/// every former Swift test under its Swift name). Here: the native store around the core, and a
/// façade that converts without changing anything.
struct AlarmCoreTests {
    func calendar(_ zone: String = "UTC") -> Calendar { var c = Calendar(identifier:.gregorian); c.timeZone = TimeZone(identifier:zone)!; return c }
    func date(_ s: String) -> Date { ISO8601DateFormatter().date(from:s)! }
    func temporary() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("alarm-\(UUID().uuidString).json") }

    @Test func failedWriteDoesNotPublish() async throws {
        let repo = LoopRepository(file:temporary(),writer:{ _,_ in throw LoopFailure.invalid("disk full") })
        await #expect(throws: LoopFailure.invalid("disk full")) { try await repo.change { $0.routines.append(Routine()) } }
        #expect(try await repo.read().routines.isEmpty)
    }
    @Test func corruptFilesArePreserved() async throws {
        let file = temporary(); defer { try? FileManager.default.removeItem(at:file) }
        let bytes = Data("broken".utf8); try bytes.write(to:file)
        await #expect(throws: (any Error).self) { try await LoopRepository(file:file).change { $0.routines = [] } }
        #expect(try Data(contentsOf:file) == bytes)
    }
    @Test func invalidChangesAreNotWritten() async throws {
        let file = temporary(); defer { try? FileManager.default.removeItem(at:file) }
        let repo = LoopRepository(file:file)
        let saved = try await repo.change { $0.routines = [Routine(), Routine(kind:.loop)] }
        #expect(try await LoopRepository(file:file).read() == saved)
        await #expect(throws: LoopFailure.invalid("Give the routine a name of up to 100 characters.")) { try await repo.change { $0.routines[0].name = " " } }
        #expect(try await LoopRepository(file:file).read() == saved)
        #expect(try await repo.read() == saved)
    }
    @Test func facadeKeepsTheSwiftNames() {
        let r = Routine()
        #expect(r.name == "Wake up"); #expect(r.kind == .alarm); #expect(r.slots.count == 1)
        #expect(r.slots[0].minute == 420); #expect(r.slots[0].weekdays == [2,3,4,5,6]); #expect(r.difficulty == 1)
        #expect(Routine(kind:.loop).delivery == .reminder)
        #expect(TimeSlot(minute:720).weekdays == [2,3,4,5,6])
        #expect(MissionKind(rawValue:"numberOrder") == .numberOrder); #expect(MissionKind(rawValue:"nope") == nil)
        #expect(EventKind.emergencyStop.rawValue == "emergencyStop"); #expect(AlarmTone.allCases.map(\.rawValue) == ["system","chime","pulse","bright"])
        #expect(AlarmDefaults().routine().snoozeMinutes == 5)
        let row = Registration(id:UUID(),routine:r,date:r.once)
        #expect(row.purpose == "schedule"); #expect(row.snoozes == 0); #expect(row.occurrenceID == nil)
        #expect(LoopEvent(routine:r,kind:.manual).routineID == r.id)
    }
    @Test func coreRefusalsReadAsSentences() {
        var r = Routine(); r.name = " "
        #expect(throws: LoopFailure.invalid("Give the routine a name of up to 100 characters.")) { try r.validate() }
        #expect(throws: LoopFailure.invalid("The data couldn’t be read because it isn’t in the correct format.")) { try BackupCodec.decode(Data("broken".utf8)) }
        #expect(LoopFailure.invalid("Snooze limit reached.").localizedDescription == "Snooze limit reached.")
    }
    @Test func calendarReachesTheCore() throws {
        var r = Routine(); r.slots = [TimeSlot(minute:150,weekdays:[1])]
        let gap = try ScheduleCompiler.occurrences(r,after:date("2026-03-08T05:00:00Z"),days:1,calendar:calendar("America/New_York"))
        #expect(gap == [date("2026-03-08T07:00:00Z")])
    }
    @Test func missionObjectsKeepTheirState() {
        let sequence = SequenceChallenge(sequence:[2,1])
        #expect(sequence.tap(2)); #expect(sequence.progress() == 1)
        #expect(!sequence.tap(0)); #expect(sequence.progress() == 0)
        let shake = ShakeCounter(); shake.sample(acceleration:1.5,timestamp:1); #expect(shake.count() == 1)
    }
}
