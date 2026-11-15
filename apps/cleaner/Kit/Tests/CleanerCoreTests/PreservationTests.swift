import Foundation
import Testing
@testable import CleanerCore

@Test func smallerIsNotEnough() {
    let before = ImageFacts(width: 4000, height: 3000, bytes: 8_000_000)
    let after = ImageFacts(width: 4000, height: 3000, bytes: 1_000_000)
    let bad = CompressionAssessment(source: before, output: after, fullResolutionPSNR: 24, worstTilePSNR: 12)
    #expect(!bad.canOfferCopy); #expect(!bad.authorizesSourceDeletion)
    let good = CompressionAssessment(source: before, output: after, fullResolutionPSNR: 45, worstTilePSNR: 36)
    #expect(good.canOfferCopy); #expect(!good.authorizesSourceDeletion)
}
@Test func localDamageCannotHideInGlobalAverage() {
    let facts = ImageFacts(width: 100, height: 100, bytes: 100_000)
    var output = facts; output.bytes = 10_000
    #expect(!CompressionAssessment(source: facts, output: output, fullResolutionPSNR: 46, worstTilePSNR: 20).canOfferCopy)
}
@Test func transformationsRequireDisclosure() {
    let source = ImageFacts(width: 100, height: 100, orientation: 6, colorSpace: "P3", hasAlpha: true, bits: 10, bytes: 100_000)
    let output = ImageFacts(width: 50, height: 50, bytes: 10_000)
    let result = CompressionAssessment(source: source, output: output, fullResolutionPSNR: 50, worstTilePSNR: 50)
    #expect(result.issues.count == 5)
}
@Test func invalidMetricAndNoSavingsFail() {
    let a = ImageFacts(width: 100, height: 100, bytes: 9000)
    #expect(!CompressionAssessment(source: a, output: a, fullResolutionPSNR: .nan, worstTilePSNR: 50).canOfferCopy)
}
private func person(_ id: String, phones: Set<String> = [], emails: Set<String> = []) -> ContactRecord {
    ContactRecord(id: id, name: id, container: "local", fields: [ContactField("givenName", id)], phoneKeys: phones, emailKeys: emails)
}
@Test func householdAndCompanyMatchesDoNotBecomeTransitiveMergeGroups() {
    let a = person("A", phones: ["+12025550100"]), b = person("B", phones: ["+12025550100"], emails: ["b@example.com"]), c = person("C", emails: ["b@example.com"])
    let matches = ContactMatching.candidates([a,b,c])
    #expect(matches.count == 2)
    #expect(!matches.contains { Set([$0.left,$0.right]) == Set(["A","C"]) })
}
@Test func unavailableNotesBlockDestructiveMergeEvenWithVerifiedArchive() {
    let plan = ContactMergePlan([person("A"),person("B")], backupVerified: true, revisionUnchanged: true)
    #expect(!plan.canDeleteSources); #expect(!plan.blockers.isEmpty); #expect(plan.conflicts == ["givenName"])
}
@Test func labelsAndMultipleValuesArePreserved() {
    var a = person("A"), b = person("B")
    a.fields = [ContactField("phone", "123", label: "home")]
    b.fields = [ContactField("phone", "123", label: "work"),ContactField("email", "b@example.com")]
    let plan = ContactMergePlan([a,b], backupVerified: true, revisionUnchanged: true)
    #expect(plan.fields.count == 3); #expect(plan.conflicts.isEmpty)
}
@Test func accountAndStaleSourceBlockersAreIndependent() {
    var a = person("A"), b = person("B"); a.inaccessibleFields = []; b.inaccessibleFields = []; b.container = "work"
    let plan = ContactMergePlan([a,b], backupVerified: false, revisionUnchanged: false)
    #expect(plan.blockers.count == 3)
}
@Test func unknownBytesAreNotZero() { let value = StorageSummary([CleanupItem(id: "a", name: "a", kind: .photo), CleanupItem(id: "b", name: "b", kind: .photo, bytes: 42)]); #expect(value.knownBytes == 42); #expect(value.unknownCount == 1) }
@Test func smartSelectionRespectsFavoritesKeepAndLater() {
    let items = (0..<5).map { CleanupItem(id: String($0), name: "x", kind: .photo, favorite: $0 == 0, fingerprint: "identical") }
    let proposed = Matching.proposedRemoval(groups: Matching.exact(items), items: items, decisions: ["1":.keep,"2":.later,"3":.remove])
    #expect(proposed == ["4"])
}
@Test func similarityNeverPreselects() {
    let items = [CleanupItem(id: "a", name: "a", kind: .photo),CleanupItem(id: "b", name: "b", kind: .photo)]
    #expect(Matching.proposedRemoval(groups: [MatchGroup(id: "s", kind: .similar, members: ["a","b"], reason: "similar")], items: items, decisions: [:]).isEmpty)
}
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

@Test func smartSelectionNeverQueuesTheLastUnqueuedKeeper() {
    let items = [CleanupItem(id: "a", name: "a", kind: .photo, fingerprint: "same"), CleanupItem(id: "b", name: "b", kind: .photo, fingerprint: "same")]
    #expect(Matching.proposedRemoval(groups: Matching.exact(items), items: items, decisions: ["a": .remove]).isEmpty)
}

@Test func unnormalizedNationalNumberIsNotMissing() {
    let record = ContactRecord(id: "a", name: "A", container: "local", fields: [ContactField("phone", "020 7946 0018")])
    #expect(!record.incomplete)
}
