#if canImport(CleanerCore)
import Foundation
import SQLite3
import Testing
import CleanerCore
@testable import CleanerPlatform

@Test func measurementsSurviveReopenAndPruneChangedOrRevokedAssets() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("ScanCache/measurements.sqlite")
    let a = CleanupItem(id: "a", name: "Private name", kind: .photo, revision: "1", bytes: 42, favorite: true, fingerprint: "hash")
    let b = CleanupItem(id: "b", name: "B", kind: .video, revision: "1", bytes: 84, fingerprint: "other")
    do { let first = MeasurementCache(url: url); try first.checkpoint(a); try first.checkpoint(b) }
    let reopened = MeasurementCache(url: url)
    #expect(try reopened.count() == 2)
    var current = a; current.fingerprint = nil; current.bytes = nil; current.favorite = false; current.name = "Current name"
    let restored = try reopened.restore([current])
    #expect(restored[0].fingerprint == "hash" && restored[0].bytes == 42)
    #expect(restored[0].name == "Current name" && !restored[0].favorite)
    #expect(try reopened.count() == 1)
    current.revision = "2"
    #expect(try reopened.restore([current])[0].fingerprint == nil)
    #expect(try reopened.count() == 0)
    #expect(try url.deletingLastPathComponent().resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
}

@Test func clearingMeasurementsDoesNotChangeSavedDecisions() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let review = ReviewStore(url: directory.appendingPathComponent("review.json"))
    _ = try await review.decide(["a": .keep], revisions: ["a": "1"])
    _ = try await review.record("Reviewed", processedSource: "original")
    let before = try Data(contentsOf: directory.appendingPathComponent("review.json"))
    let cache = MeasurementCache(url: directory.appendingPathComponent("ScanCache/measurements.sqlite"))
    try cache.checkpoint(CleanupItem(id: "a", name: "A", kind: .photo, revision: "1", bytes: 42, fingerprint: "hash"))
    try cache.clear()
    #expect(try cache.count() == 0)
    #expect(try Data(contentsOf: directory.appendingPathComponent("review.json")) == before)
}

@Test func corruptCheckpointFailsWithoutSilentlyDiscardingProgress() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("measurements.sqlite")
    let item = CleanupItem(id: "a", name: "A", kind: .photo, revision: "1", bytes: 42, fingerprint: "hash")
    do { let cache = MeasurementCache(url: url); try cache.checkpoint(item) }
    var db: OpaquePointer?
    #expect(sqlite3_open(url.path, &db) == SQLITE_OK)
    #expect(sqlite3_exec(db, "UPDATE measurements SET payload='bad'", nil, nil, nil) == SQLITE_OK)
    sqlite3_close(db)
    let cache = MeasurementCache(url: url)
    #expect(throws: (any Error).self) { try cache.restore([item]) }
    #expect(try cache.count() == 1)
    try cache.clear()
    #expect(try cache.count() == 0)
}

@Test func checkpointStorageFailureLeavesReviewRecordsUntouched() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let reviewURL = directory.appendingPathComponent("review.json")
    _ = try await ReviewStore(url: reviewURL).decide(["a": .keep], revisions: ["a": "1"])
    let before = try Data(contentsOf: reviewURL)
    let obstruction = directory.appendingPathComponent("not-a-directory")
    try Data("retain".utf8).write(to: obstruction)
    let cache = MeasurementCache(url: obstruction.appendingPathComponent("measurements.sqlite"))
    #expect(throws: (any Error).self) {
        try cache.checkpoint(CleanupItem(id: "a", name: "A", kind: .photo, revision: "1", bytes: 42, fingerprint: "hash"))
    }
    #expect(try Data(contentsOf: reviewURL) == before)
    #expect(try Data(contentsOf: obstruction) == Data("retain".utf8))
}
#endif
