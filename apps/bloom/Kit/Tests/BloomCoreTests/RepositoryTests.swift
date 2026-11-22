import BloomCore
import XCTest

final class RepositoryTests: XCTestCase, @unchecked Sendable {
  func testHealthImportRechecksDatesAndPersistsOnlyReviewedFlow() async throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
      .appendingPathComponent("health.enc")
    defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
    let key = Data(repeating: 7, count: 32)
    let repo = BloomRepository(file: url, key: key)
    let incoming = [HealthFlowSample(day: "2026-11-16", flow: "heavy", periodStart: true)]
    let json = try bloomHealthReview(archive: bloomEmpty(), samples: BloomCodec.string(incoming))
    let preview = try BloomCodec.decode(Archive.self, from: Data(json.utf8))
    let saved = try await repo.importHealth(preview)
    XCTAssertEqual(saved.days.count, 1)
    XCTAssertEqual(saved.days[0].flow, "heavy")
    XCTAssertTrue(saved.days[0].notes.isEmpty)
    let repeated = try await repo.importHealth(preview)
    XCTAssertEqual(repeated, saved)
    let reopened = BloomRepository(file: url, key: key)
    let disk = try await reopened.read()
    XCTAssertEqual(disk, saved)
  }
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
  func testWatchCommitIsEncryptedIdempotentAndRequiresOptIn() async throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("watch.enc")
    defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
    let key = Data(repeating: 3, count: 32)
    var seed = Archive(); seed.profile.watch_enabled = true; seed.profile.discreet = true
    var plan = Plan(); plan.name = "Private medication"; plan.pattern = "daily"; plan.start = BloomDate.day(Date())
    seed.plans = [plan]
    let repo = BloomRepository(file: url, seed: seed, key: key)
    let dash = try await repo.dashboard()
    let occurrence = try XCTUnwrap(dash.today_doses.first)
    let request = WatchRequest(command: try Command(op: "dose", record: Dose(occurrence, status: "taken"), revision: 0))
    let saved = try await repo.applyWatch(request, paid: false)
    let retry = try await repo.applyWatch(request, paid: false)
    XCTAssertEqual(saved, retry); XCTAssertEqual(saved.doses.count, 1)
    XCTAssertFalse(String(decoding: try Data(contentsOf: url), as: UTF8.self).contains("Private medication"))
    let reopened = try await BloomRepository(file: url, key: key).read()
    XCTAssertEqual(reopened, saved)
    var off = saved.profile; off.watch_enabled = false
    _ = try await repo.apply(Command(op: "profile", record: off, revision: saved.revision), paid: false)
    do { _ = try await repo.applyWatch(request, paid: false); XCTFail("Disabled companion accepted an action") } catch {}
  }

}
