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
}
