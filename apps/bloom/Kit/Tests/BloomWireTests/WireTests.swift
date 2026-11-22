import XCTest

@testable import BloomWire

final class WireTests: XCTestCase {
  func testCommandRoundTrip() throws {
    var day = DayLog()
    day.notes = "private 🪴"
    let command = try Command(op: "day", record: day, revision: 42)
    let data = try BloomCodec.encode(command)
    let restored = try BloomCodec.decode(Command.self, from: data)
    XCTAssertEqual(restored.request_id, command.request_id)
    XCTAssertEqual(restored.expected_revision, 42)
    XCTAssertEqual(try BloomCodec.encode(restored), data)
  }
  func testEmptyArchiveRoundTrip() throws {
    let a = Archive()
    XCTAssertEqual(try BloomCodec.decode(Archive.self, from: BloomCodec.encode(a)), a)
    XCTAssertFalse(a.profile.watch_enabled)
    XCTAssertFalse(a.profile.widgets_enabled)
  }
  func testWatchSnapshotFreshnessOrderingAndPrivacy() throws {
    let now = Date(timeIntervalSince1970: 1_794_960_000)
    var fresh = WatchSnapshot(enabled: true, cycleDay: 9, doses: [], canAdd: false)
    fresh.generated = Int64(now.timeIntervalSince1970); fresh.revision = 5
    XCTAssertTrue(fresh.fresh(now: now))
    var corrupt = fresh; corrupt.generated = Int64.min
    XCTAssertFalse(corrupt.fresh(now: now))
    var future = fresh; future.generated += 100
    XCTAssertFalse(future.fresh(now: now)); XCTAssertFalse(future.replaces(fresh, now: now))
    var yesterday = fresh; yesterday.generated -= 86_400
    XCTAssertFalse(yesterday.fresh(now: now)); XCTAssertFalse(yesterday.replaces(fresh, now: now))
    var off = fresh; off.enabled = false; off.cycle_day = nil
    XCTAssertTrue(off.replaces(fresh, now: now)); XCTAssertFalse(fresh.replaces(off, now: now))
    XCTAssertFalse(off.fresh(now: now))
    var old = fresh; old.revision = 4
    XCTAssertFalse(old.replaces(fresh, now: now))
    let legacy = #"{"version":1,"generated":1794960000,"enabled":true,"doses":[],"can_add":false}"#
    XCTAssertNil(try BloomCodec.decode(WatchSnapshot.self, from: Data(legacy.utf8)).revision)
  }

}
