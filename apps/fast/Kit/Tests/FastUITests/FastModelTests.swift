import Foundation
import Testing
import SevenKit
import FastCore
@testable import FastUI

@MainActor private func model(_ preset: LocalSource.Preset, seed: FastArchive = FastArchive()) async -> FastModel {
    let defaults = UserDefaults(suiteName: "FastModelTests.\(UUID())")!
    let source = LocalSource(defaults: defaults); source.apply(preset)
    let store = Store(config: SevenConfig(trialProductID: "test.trial", unlockProductID: "test.unlock"), source: source, defaults: defaults)
    let model = FastModel(store: store, repository: ArchiveStore(file: nil, seed: seed), reconcile: { _ in "" })
    await model.start(); return model
}

@Test @MainActor func expiredTrialCanFinishEditAndExportButNotStart() async throws {
    var seed = FastArchive()
    let active = FastRecord(start: Date().addingTimeInterval(-7_200))
    seed.fasts = [active]
    let model = await model(.expired, seed: seed)
    #expect(!model.store.paidFeaturesAvailable)
    var completed = active; completed.end = Date(); completed.reflection = "Finished early."
    #expect(await model.saveFast(completed))
    #expect(!(await model.saveFast(FastRecord(start: Date()))))
    #expect(model.archive.fasts.count == 1)
    #expect(ArchiveCodec.csv(model.archive).contains("Finished early."))
    #expect(try ArchiveCodec.decode(ArchiveCodec.encode(model.archive), now: Date()).fasts.count == 1)
}

@Test @MainActor func deletedFastDoesNotDeleteObservations() async {
    var seed = FastArchive(); let fast = FastRecord(start: Date().addingTimeInterval(-3_600), end: Date())
    seed.fasts = [fast]; seed.journal = [JournalEntry(date: Date(), fastID: fast.id, text: "Keep me")]
    let model = await model(.unlocked, seed: seed)
    #expect(await model.change { $0.deleteFast(fast.id) })
    #expect(model.archive.journal[0].text == "Keep me")
    #expect(model.archive.journal[0].fastID == nil)
}

@Test @MainActor func phoneWatchHandlerHonorsExpiryAndReturnsAuthoritativeSnapshot() async throws {
    var seed = FastArchive()
    seed.fasts = [FastRecord(start: Date().addingTimeInterval(-60))]
    let model = await model(.expired, seed: seed)
    let end = FastWatchRequest(action: .end, revision: model.archive.companionRevision, activeID: model.archive.active?.id)
    let endReply = try FastWatchCodec.decode(FastWatchReply.self, from: await model.receiveWatch(FastWatchCodec.encode(end)))
    #expect(endReply.requestID == end.id)
    #expect(endReply.failure == nil)
    #expect(endReply.snapshot?.activeID == nil)
    let start = FastWatchRequest(action: .start, revision: model.archive.companionRevision)
    let startReply = try FastWatchCodec.decode(FastWatchReply.self, from: await model.receiveWatch(FastWatchCodec.encode(start)))
    #expect(startReply.failure == .locked)
    #expect(model.archive.fasts.count == 1)
}

@Test @MainActor func phoneWatchHandlerRejectsMalformedPayload() async throws {
    let model = await model(.unlocked)
    let reply = try FastWatchCodec.decode(FastWatchReply.self, from: await model.receiveWatch(Data([0, 1, 2])))
    #expect(reply.failure != nil)
    #expect(model.archive.fasts.isEmpty)
}

@Test @MainActor func waterEditsSurviveExpiryWhileNewDrinksStayGated() async {
    var seed = FastArchive(); let entry = MetricEntry(date: Date(), kind: .water, value: 250)
    seed.metrics = [entry]
    let model = await model(.expired, seed: seed)
    #expect(!(await model.saveMetric(MetricEntry(date: Date(), kind: .water, value: 500))))
    var edited = entry; edited.value = 150
    #expect(await model.saveMetric(edited))
    #expect(model.archive.metrics == [edited])
    #expect(await model.change { $0.metrics.removeAll { $0.id == entry.id } })
    #expect(model.archive.metrics.isEmpty)
    // Undo puts the same drink back after expiry: a restore, not a new record.
    #expect(await model.restoreMetric(edited))
    #expect(model.archive.metrics == [edited])
    #expect(!(await model.restoreMetric(edited)))
    #expect(model.archive.metrics.count == 1)
}
