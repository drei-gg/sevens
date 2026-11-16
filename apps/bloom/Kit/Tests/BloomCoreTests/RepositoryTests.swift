import BloomCore
import XCTest

final class RepositoryTests: XCTestCase, @unchecked Sendable {
  func testEncryptedPersistenceAndTamperingFailClosed() async throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
      .appendingPathComponent("records.enc")
    defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
    let key = Data(repeating: 9, count: 32)
    let repo = BloomRepository(file: url, key: key)
    var day = DayLog()
    day.notes = "Private persistence sentinel"
    let saved = try await repo.apply(Command(op: "day", record: day, revision: 0), paid: true)
    XCTAssertEqual(saved.days.count, 1)
    let disk = try Data(contentsOf: url)
    XCTAssertFalse(String(decoding: disk, as: UTF8.self).contains(day.notes))
    let reopened = BloomRepository(file: url, key: key)
    let loaded = try await reopened.read()
    XCTAssertEqual(loaded, saved)
    var bad = disk
    bad[bad.count - 1] ^= 1
    try bad.write(to: url)
    let broken = BloomRepository(file: url, key: key)
    do {
      _ = try await broken.read()
      XCTFail("Corruption must fail closed")
    } catch {}
    XCTAssertEqual(try Data(contentsOf: url), bad)
  }
  func testRejectedEditLeavesArchiveUnchanged() async throws {
    let repo = BloomRepository(file: nil)
    var day = DayLog()
    day.notes = "first"
    let saved = try await repo.apply(Command(op: "day", record: day, revision: 0), paid: true)
    day.notes = "stale"
    do {
      _ = try await repo.apply(Command(op: "day", record: day, revision: 0), paid: true)
      XCTFail("Stale edit accepted")
    } catch {}
    let after = try await repo.read()
    XCTAssertEqual(after, saved)
    let dash = try await repo.dashboard()
    XCTAssertEqual(dash.today, BloomDate.day(Date()))
  }
}
