import Foundation
import Testing
@testable import FastCore

@Test func watchCommandsAreIdempotentAndRejectStaleState() async throws {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let store = ArchiveStore(file: nil)
    let original = try await store.read(now: now)
    let request = FastWatchRequest(sentAt: now, action: .water, revision: original.companionRevision)
    let first = try await store.transact(now: now) { try $0.applyWatch(request, canCreateRecords: true, now: now) }
    let repeatResult = try await store.transact(now: now) { try $0.applyWatch(request, canCreateRecords: true, now: now) }
    #expect(first == repeatResult)
    #expect(repeatResult.metrics.count == 1)
    var stale = request; stale.id = UUID()
    let oldRequest = stale
    await #expect(throws: FastWatchFailure.stale) {
        try await store.transact(now: now) { try $0.applyWatch(oldRequest, canCreateRecords: true, now: now) }
    }
    let bytes = try ArchiveCodec.encode(first)
    var restored = try ArchiveCodec.decode(bytes, now: now)
    try restored.applyWatch(request, canCreateRecords: false, now: now)
    #expect(restored.metrics.count == 1)
}

@Test func watchCannotCreateAfterExpiryButCanFinish() throws {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    var archive = FastArchive()
    let start = FastWatchRequest(sentAt: now, action: .start, revision: archive.companionRevision)
    #expect(throws: FastWatchFailure.locked) { try archive.applyWatch(start, canCreateRecords: false, now: now) }
    try archive.applyWatch(start, canCreateRecords: true, now: now)
    let end = FastWatchRequest(sentAt: now, action: .end, revision: archive.companionRevision, activeID: archive.active?.id)
    try archive.applyWatch(end, canCreateRecords: false, now: now.addingTimeInterval(1))
    #expect(archive.active == nil)
    #expect(archive.fasts.count == 1)
}

@Test func watchRejectsExpiredMalformedAndUnsupportedRequests() throws {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    var archive = FastArchive()
    var request = FastWatchRequest(sentAt: now.addingTimeInterval(-121), action: .water, revision: archive.companionRevision)
    #expect(throws: FastWatchFailure.expired) { try archive.applyWatch(request, canCreateRecords: true, now: now) }
    request.sentAt = now.addingTimeInterval(31)
    #expect(throws: FastWatchFailure.expired) { try archive.applyWatch(request, canCreateRecords: true, now: now) }
    request.sentAt = now; request.version = 2
    #expect(throws: FastWatchFailure.unsupported) { try archive.applyWatch(request, canCreateRecords: true, now: now) }
    request.version = 1
    try archive.applyWatch(request, canCreateRecords: true, now: now)
    request.action = .start
    #expect(throws: FastWatchFailure.malformed) { try archive.applyWatch(request, canCreateRecords: true, now: now) }
    #expect(throws: FastWatchFailure.malformed) {
        try FastWatchCodec.decode(FastWatchRequest.self, from: Data(repeating: 0, count: 32_769))
    }
}

@Test func watchDoesNotAcknowledgeFailedDiskWrite() async throws {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let store = ArchiveStore(file: url, writer: { _, _ in throw TrackerError.ReadOnly })
    let original = try await store.read(now: now)
    let request = FastWatchRequest(sentAt: now, action: .water, revision: original.companionRevision)
    await #expect(throws: TrackerError.ReadOnly) {
        try await store.transact(now: now) { try $0.applyWatch(request, canCreateRecords: true, now: now) }
    }
    #expect(try await store.read(now: now) == original)
}

@Test func archivesBeforeCompanionSupportStillDecode() throws {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let data = try ArchiveCodec.encode(FastArchive())
    var object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    object.removeValue(forKey: "companionReceipts"); object.removeValue(forKey: "companionRevision")
    let decoded = try ArchiveCodec.decode(JSONSerialization.data(withJSONObject: object), now: now)
    #expect(decoded.companionReceipts.isEmpty)
    #expect(decoded.fasts.isEmpty)
}

@Test func receiptReplaySurvivesFractionalDateBackupPrecision() throws {
    let now = Date(timeIntervalSinceReferenceDate: 815_465_950.776_553_2)
    var archive = FastArchive()
    let request = FastWatchRequest(sentAt: now, action: .water, revision: archive.companionRevision)
    try archive.applyWatch(request, canCreateRecords: true, now: now)
    var restored = try ArchiveCodec.decode(ArchiveCodec.encode(archive), now: now)
    try restored.applyWatch(request, canCreateRecords: true, now: now.addingTimeInterval(1))
    #expect(restored.metrics.count == 1)
}

@Test func committedWatchRetryDoesNotRequireAnotherDiskWrite() async throws {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: url) }
    var archive = FastArchive()
    let request = FastWatchRequest(sentAt: now, action: .water, revision: archive.companionRevision)
    try archive.applyWatch(request, canCreateRecords: true, now: now)
    try ArchiveCodec.encode(archive).write(to: url)
    let relaunched = ArchiveStore(file: url, writer: { _, _ in throw TrackerError.ReadOnly })
    let reply = try await relaunched.transact(now: now) { try $0.applyWatch(request, canCreateRecords: true, now: now) }
    #expect(reply.metrics.count == 1)
}
