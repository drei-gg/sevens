import Foundation
import Testing
@testable import SevenBackup

struct RefusingWriter: BackupFolderWriter {
    func write(source: URL, directory: URL, filename: String) throws { throw CocoaError(.fileWriteNoPermission) }
    func remove(directory: URL, filename: String) throws {}
}
@Test func durableFailureRetriesAfterRelaunchAndRetainsPreviousVersions() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let destination = root.appendingPathComponent("provider"), queueURL = root.appendingPathComponent("queue")
    try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
    let source = root.appendingPathComponent("ciphertext"), id = UUID()
    try Data("already encrypted sample payload".utf8).write(to: source)
    let failing = AutomaticBackup(root: queueURL, writer: RefusingWriter())
    try await failing.configure(target: id, directory: destination)
    let first = String(repeating: "a", count: 64)
    try await failing.enqueue(source: source, target: id, revision: first, fileExtension: "vault7")
    try await failing.deliverPending(now: Date(timeIntervalSince1970: 100))
    let failure = try await failing.status(target: id)
    #expect(failure?.failure != nil); #expect(failure?.copiedAt == nil); #expect(try await failing.hasPending())
    let restarted = AutomaticBackup(root: queueURL)
    let abandoned = queueURL.appendingPathComponent("preparing")
    try FileManager.default.createDirectory(at: abandoned, withIntermediateDirectories: true)
    try Data("interrupted encrypted artifact".utf8).write(to: abandoned.appendingPathComponent("partial"))
    try await restarted.prepare()
    #expect(!FileManager.default.fileExists(atPath: abandoned.path))
    try await restarted.deliverPending(now: Date(timeIntervalSince1970: 101))
    #expect(try await restarted.hasPending()) // respects backoff
    try await restarted.deliverPending(force: true)
    #expect(try await restarted.status(target: id)?.copiedRevision == first)
    #expect(!(try await restarted.hasPending()))
    #expect(try Data(contentsOf: destination.appendingPathComponent(id.uuidString + "-" + first + ".vault7")) == Data(contentsOf: source))
    try await failing.enqueue(source: source, target: id, revision: String(repeating: "f", count: 64), fileExtension: "vault7")
    try await failing.deliverPending(force: true)
    #expect(try await failing.status(target: id)?.copiedRevision == first)
    #expect(FileManager.default.fileExists(atPath: destination.appendingPathComponent(id.uuidString + "-" + first + ".vault7").path))
    let unrelated = destination.appendingPathComponent("keep-me"); try Data().write(to: unrelated)
    for letter in ["b", "c", "d"] {
        try await restarted.enqueue(source: source, target: id, revision: String(repeating: letter, count: 64), fileExtension: "vault7")
        try await restarted.deliverPending(force: true)
    }
    #expect(try FileManager.default.contentsOfDirectory(atPath: destination.path).count == 4)
    #expect(!FileManager.default.fileExists(atPath: destination.appendingPathComponent(id.uuidString + "-" + first + ".vault7").path))
    try await restarted.disable(target: id)
    #expect(try await restarted.status(target: id) == nil)
    #expect(try FileManager.default.contentsOfDirectory(atPath: destination.path).count == 4)
}
@Test func cancelledDeliveryAndSupersededQueueDoNotPublishPartialFiles() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let destination = root.appendingPathComponent("provider"); try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
    let source = root.appendingPathComponent("ciphertext"); try Data(repeating: 77, count: 2_100_000).write(to: source)
    let queue = AutomaticBackup(root: root.appendingPathComponent("queue")), id = UUID()
    try await queue.configure(target: id, directory: destination)
    for letter in ["a", "b"] { try await queue.enqueue(source: source, target: id, revision: String(repeating: letter, count: 64), fileExtension: "vault7") }
    let operation = Task { try Task.checkCancellation(); try await queue.deliverPending(force: true) }; operation.cancel()
    _ = try? await operation.value
    // Delivery is either cancelled before commit, or completes atomically before cancellation wins.
    let contents = try FileManager.default.contentsOfDirectory(atPath: destination.path)
    #expect(contents.allSatisfy { !$0.hasPrefix(".backup-") })
    try await queue.deliverPending(force: true)
    #expect(try await queue.status(target: id)?.copiedRevision == String(repeating: "b", count: 64))
    #expect(try FileManager.default.contentsOfDirectory(atPath: destination.path).count == 1)
}
