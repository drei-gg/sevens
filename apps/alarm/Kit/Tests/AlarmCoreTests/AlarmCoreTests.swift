import Foundation
import Testing
@testable import AlarmCore

struct AlarmCoreTests {
    func calendar(_ zone: String = "UTC") -> Calendar { var c = Calendar(identifier:.gregorian); c.timeZone = TimeZone(identifier:zone)!; return c }
    func date(_ s: String) -> Date { ISO8601DateFormatter().date(from:s)! }
    @Test func definingMWFExample() throws {
        var r = Routine(kind:.loop); r.slots = [TimeSlot(minute:420,weekdays:[2,6]),TimeSlot(minute:720,weekdays:[2,4,6]),TimeSlot(minute:840,weekdays:[2,4,6])]
        let compiled = try ScheduleCompiler.registrations(r)
        #expect(compiled.count == 3)
        let dates = try ScheduleCompiler.occurrences(r,after:date("2026-11-08T00:00:00Z"),calendar:calendar())
        #expect(dates.count == 8)
        #expect(!dates.contains(date("2026-11-11T07:00:00Z")))
        r.slots[1].minute = 750
        let edited = try ScheduleCompiler.occurrences(r,after:date("2026-11-08T00:00:00Z"),calendar:calendar())
        #expect(edited.filter { calendar().component(.hour,from:$0) == 12 && calendar().component(.minute,from:$0) == 30 }.count == 3)
        #expect(r.slots.count == 3)
    }
    @Test func overnightSundayRollsToMonday() throws {
        var r = Routine(); r.timing = .window; r.windowStart = 1380; r.windowEnd = 60; r.intervalMinutes = 60; r.windowDays = [1]
        let rows = try ScheduleCompiler.registrations(r)
        #expect(rows.first(where:{$0.minute == 0})?.weekdays == [2])
        #expect(rows.first(where:{$0.minute == 1380})?.weekdays == [1])
        #expect(rows.count == 3)
    }
    @Test func duplicatesCoalesceAndIDsSurviveEdits() throws {
        var r = Routine(); r.slots = [TimeSlot(minute:420,weekdays:[2]),TimeSlot(minute:420,weekdays:[2,4])]
        let rows = try ScheduleCompiler.registrations(r); r.name = "New title"
        #expect(rows.count == 1); #expect(rows[0].weekdays == [2,4]); #expect(try ScheduleCompiler.registrations(r)[0].id == rows[0].id)
    }
    @Test func DSTGapAndFold() throws {
        var r = Routine(); r.slots = [TimeSlot(minute:150,weekdays:[1])]
        let gap = try ScheduleCompiler.occurrences(r,after:date("2026-03-08T05:00:00Z"),days:1,calendar:calendar("America/New_York"))
        #expect(gap == [date("2026-03-08T07:00:00Z")])
        r.slots[0].minute = 90
        let fold = try ScheduleCompiler.occurrences(r,after:date("2026-11-01T04:00:00Z"),days:1,calendar:calendar("America/New_York"))
        #expect(fold == [date("2026-11-01T05:30:00Z")])
    }
    @Test func endedOnceAndDisabledProduceNothing() throws {
        var r = Routine(); r.timing = .once; r.once = .distantPast
        #expect(try ScheduleCompiler.registrations(r).isEmpty)
        r.timing = .weekly; r.enabled = false
        #expect(try ScheduleCompiler.registrations(r).isEmpty)
    }
    @Test func invalidAndOversizedWindowsReject() {
        var r = Routine(); r.slots[0].weekdays = []
        #expect(throws: (any Error).self) { try r.validate() }
        r = Routine(); r.timing = .window; r.windowStart = 0; r.windowEnd = 1439; r.intervalMinutes = 5
        #expect(throws: (any Error).self) { try ScheduleCompiler.registrations(r) }
    }
    @Test func missionChecksAreExact() {
        #expect(ArithmeticChallenge(left:12,right:8).accepts("20"))
        #expect(!ArithmeticChallenge(left:12,right:8).accepts("21"))
        #expect(MissionCheck.phrase(" I am awake ",expected:"I am awake"))
        #expect(!MissionCheck.code("https://wrong",expected:"https://right"))
        #expect(!MissionCheck.code("",expected:""))
    }
    @Test func roundTripAndDormantImport() throws {
        var a = LoopArchive(); a.routines = [Routine()]; a.events = [LoopEvent(routine:a.routines[0],kind:.manual)]
        #expect(try BackupCodec.decode(BackupCodec.encode(a)) == a)
        let merged = try BackupCodec.merge(a,into:LoopArchive())
        #expect(merged.routines[0].enabled == false); #expect(merged.installed.isEmpty)
        #expect(try BackupCodec.merge(a,into:merged).events.count == 1)
        var conflict = a; conflict.events[0].note = "Changed"
        #expect(throws: (any Error).self) { try BackupCodec.merge(conflict,into:a) }
    }
    @Test func failedWriteDoesNotPublish() async throws {
        let repo = LoopRepository(file:URL(fileURLWithPath:"/tmp/alarm-test-\(UUID()).json"),writer:{ _,_ in throw LoopError.invalid("disk full") })
        await #expect(throws: (any Error).self) { try await repo.change { $0.routines.append(Routine()) } }
        #expect(try await repo.read().routines.isEmpty)
    }
    @Test func corruptAndFutureFilesArePreserved() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:file) }
        let bytes = Data("broken".utf8); try bytes.write(to:file)
        let repo = LoopRepository(file:file)
        await #expect(throws: (any Error).self) { try await repo.change { $0.routines = [] } }
        #expect(try Data(contentsOf:file) == bytes)
        var a = LoopArchive(); a.version = 2
        #expect(throws: (any Error).self) { try BackupCodec.decode(BackupCodec.encode(a)) }
    }
    @Test func csvEscapesFormulaAndQuotes() {
        var a = LoopArchive(); var r = Routine(); r.name = "=formula,\"x\""; a.events = [LoopEvent(routine:r,kind:.manual)]
        #expect(BackupCodec.csv(a).contains("\"'=formula,\"\"x\"\"\""))
    }
}
