import Foundation
import Testing
@testable import CleanerCore

// The rules themselves are tested in the core (apps/cleaner/core/tests/swift_parity.rs ports these
// tests one to one). Here: the native review store, and the façade's conversions.

@Test func durableDecisionsSurviveRestartAndStaleDeletionFails() async throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let url = folder.appendingPathComponent("review.json")
    let store = ReviewStore(url: url)
    _ = try await store.decide(["a": .remove], revisions: ["a":"v1"])
    let reopened = try await ReviewStore(url: url).read()
    #expect(reopened.decisions["a"] == .remove)
    #expect(throws: CleanerError.self) { try MutationGuard.validateRemoval(ids: ["a"], items: [CleanupItem(id: "a", name: "a", kind: .photo, revision: "v2")], state: reopened) }
    try MutationGuard.validateRemoval(ids: ["a"], items: [CleanupItem(id: "a", name: "a", kind: .photo, revision: "v1")], state: reopened)
}
@Test func corruptLedgerCannotSilentlyReset() async throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: url) }; try Data("bad".utf8).write(to: url)
    await #expect(throws: (any Error).self) { try await ReviewStore(url: url).read() }
}
@Test func historyIsRecordedNewestFirstWithProcessedSources() async throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let store = ReviewStore(url: folder.appendingPathComponent("review.json"))
    _ = try await store.record("first")
    let state = try await store.record("second", processedSource: "a|1")
    #expect(state.history.map(\.message) == ["second", "first"])
    #expect(state.processedSources == ["a|1"])
}
@Test func facadeConvertsWithoutDeciding() {
    let item = CleanupItem(id: "a", name: "a", kind: .photo, width: 4000, height: 3000, tags: ["b", "a"])
    #expect(item.revision.isEmpty && item.bytes == nil && item.width == 4000 && item.tags == ["a", "b"])
    #expect(ReviewDecision.allCases.map(\.rawValue) == ["undecided", "keep", "later", "remove"])
    #expect(CleanerError.blocked("Nothing selected.").cleanerMessage == "Nothing selected.")
    #expect(CleanerError.Unreadable.cleanerMessage == "This file cannot be read.")
    let facts = ImageFacts(width: 100, height: 100, bytes: 100_000)
    var output = facts; output.bytes = 10_000
    let assessment = CompressionAssessment(source: facts, output: output, fullResolutionPSNR: 46, worstTilePSNR: 36)
    #expect(assessment.fullResolutionPSNR == 46 && assessment.canOfferCopy && assessment.savings == 90_000 && !assessment.authorizesSourceDeletion)
    let record = ContactRecord(id: "a", name: "A", container: "local", fields: [ContactField("phone", "1")], phoneKeys: ["2", "1"])
    #expect(record.phoneKeys == ["1", "2"] && record.inaccessibleFields == ["notes"] && !record.incomplete)
    #expect(ContactMatching.crowdedIdentifierCount([record]) == 0)
}

@Test func recordedCopyIsAtomicAndRetryDoesNotDuplicateHistory() async throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let url = folder.appendingPathComponent("review.json")
    let store = ReviewStore(url: url)
    let source = CleanupItem(id: "a", name: "Original", kind: .photo, revision: "r1")
    let before = try await store.decide(["a": .keep], revisions: ["a": "r1"])
    let valid = try Data(contentsOf: url)
    try Data("broken".utf8).write(to: url)
    await #expect(throws: (any Error).self) { try await store.recordCopy(source: source, outputID: "copy") }
    #expect(try Data(contentsOf: url) == Data("broken".utf8))
    try valid.write(to: url)
    _ = try await store.recordCopy(source: source, outputID: "copy")
    _ = try await store.recordCopy(source: source, outputID: "copy")
    let readback = try await ReviewStore(url: url).read()
    #expect(readback.decisions == before.decisions)
    #expect(readback.processedSources == ["a|r1", "output:copy"])
    #expect(readback.history.count == 1)
}
