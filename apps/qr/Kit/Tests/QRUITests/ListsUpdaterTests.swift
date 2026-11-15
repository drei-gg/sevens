import CryptoKit
import Foundation
import Testing
import QRCore
@testable import QRUI

/// SPEC §4.3: newer lists are used only when signed by a key built into the app, understood,
/// newer, well-formed and small. Everything else keeps the lists in use.
@Suite struct ListsUpdaterTests {
    static func refusal(_ result: ListsUpdateResult) -> ListsBundleError? {
        if case .failed(.refused(let error)) = result { return error }
        return nil
    }

    // MARK: Accept

    @Test func listsCorrectlySignedNewerBundleIsAcceptedSavedAndLoaded() {
        let rig = ListsTestRig()
        let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 101))
        let result = rig.updater().accept(bytes, signature: rig.sign(bytes))
        #expect(result == .updated(ListsVersion(schema: 1, version: 101, built: "2026-12-01")))
        #expect(rig.store.read() == bytes)
        #expect(rig.appliedCalls == [bytes])
    }

    @Test func listsEitherCompiledInKeyMaySign() {
        let rig = ListsTestRig(), next = Curve25519.Signing.PrivateKey()
        let updater = rig.updater(configuration: rig.configuration(extraKeys: [ListsTestRig.hex(next.publicKey.rawRepresentation)]))
        let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 105))
        let signature = (try? next.signature(for: bytes)) ?? Data()
        #expect(updater.accept(bytes, signature: signature) == .updated(ListsVersion(schema: 1, version: 105, built: "2026-12-01")))
    }

    // MARK: Refuse

    @Test func listsWrongKeyIsRefused() {
        let rig = ListsTestRig(), stranger = Curve25519.Signing.PrivateKey()
        let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 101))
        let forged = (try? stranger.signature(for: bytes)) ?? Data()
        #expect(Self.refusal(rig.updater().accept(bytes, signature: forged)) == .BadSignature)
        #expect(rig.store.read() == nil)
        #expect(rig.appliedCalls.isEmpty)
    }

    @Test func listsOneAlteredByteIsRefused() {
        let rig = ListsTestRig()
        var bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 101))
        let signature = rig.sign(bytes)
        bytes[bytes.count / 2] ^= 0x01
        #expect(Self.refusal(rig.updater().accept(bytes, signature: signature)) == .BadSignature)
        #expect(rig.store.read() == nil)
    }

    @Test func listsWrongSizedSignatureIsRefused() {
        let rig = ListsTestRig()
        let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 101))
        #expect(Self.refusal(rig.updater().accept(bytes, signature: Data())) == .BadSignature)
        #expect(Self.refusal(rig.updater().accept(bytes, signature: rig.sign(bytes) + Data([0]))) == .BadSignature)
    }

    @Test func listsSameOrLowerVersionIsARollbackAndRefused() {
        let rig = ListsTestRig()
        for version in [100, 99] {
            let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: version))
            #expect(Self.refusal(rig.updater().accept(bytes, signature: rig.sign(bytes))) == .NotNewer(installed: 100, offered: Int64(version)))
        }
        #expect(rig.store.read() == nil)
        #expect(rig.appliedCalls.isEmpty)
    }

    @Test func listsUnknownSchemaIsRefused() {
        let rig = ListsTestRig()
        let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 101, schema: 2))
        #expect(Self.refusal(rig.updater().accept(bytes, signature: rig.sign(bytes))) == .UnknownSchema(schema: 2))
    }

    @Test func listsUnknownSchemaWithAnotherShapeIsStillTheSchemaReason() {
        let rig = ListsTestRig()
        let bytes = Data(#"{"schema":7,"version":999,"built":"2027-01-01","files":[1,2,3]}"#.utf8)
        #expect(Self.refusal(rig.updater().accept(bytes, signature: rig.sign(bytes))) == .UnknownSchema(schema: 7))
    }

    @Test func listsBadShapeIsRefusedWithTheFileName() {
        let rig = ListsTestRig()
        var bundle = ListsTestRig.bundle(version: 101)
        bundle.files.shorteners = ["bit.ly"]
        let bytes = ListsTestRig.bytes(bundle)
        #expect(Self.refusal(rig.updater().accept(bytes, signature: rig.sign(bytes))) == .BadShape(file: "shorteners"))
        #expect(rig.store.read() == nil)
    }

    @Test func listsSignedGarbageIsUnreadable() {
        let rig = ListsTestRig()
        let bytes = Data("hello".utf8)
        #expect(Self.refusal(rig.updater().accept(bytes, signature: rig.sign(bytes))) == .Unreadable)
    }

    @Test func listsOversizeIsRefusedBeforeAnythingIsDecoded() {
        let rig = ListsTestRig()
        let bytes = Data(count: ListsBundle.maxBytes + 1)
        #expect(Self.refusal(rig.updater().accept(bytes, signature: rig.sign(bytes))) == .TooLarge)
    }

    // MARK: Network

    @Test func listsTapFetchesTwoFilesAndInstallsTheNewerLists() async {
        let rig = ListsTestRig()
        let base = "https://raw.githubusercontent.com/acme-net1/kit/lists/"
        let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 102))
        PeekStubProtocol.register(base + "lists.json", .ok(body: bytes))
        PeekStubProtocol.register(base + "lists.json.sig", .ok(body: rig.sign(bytes)))
        let result = await rig.updater(owner: "acme-net1", repo: "kit").check()
        #expect(result == .updated(ListsVersion(schema: 1, version: 102, built: "2026-12-01")))
        #expect(rig.store.read() == bytes)
        for name in ["lists.json", "lists.json.sig"] {
            #expect(PeekStubProtocol.methods(of: base + name) == ["GET"])
            let headers = PeekStubProtocol.requests(to: base + name).first ?? [:]
            #expect(headers["User-Agent"] == PeekClient.userAgent)
            #expect(headers["Cookie"] == nil && headers["Authorization"] == nil && headers["Referer"] == nil)
        }
    }

    @Test func listsAlreadyCurrentSaysNoNewerLists() async {
        let rig = ListsTestRig()
        let base = "https://raw.githubusercontent.com/acme-net2/kit/lists/"
        let bytes = ListsTestRig.bytes(ListsTestRig.bundle(version: 100))
        PeekStubProtocol.register(base + "lists.json", .ok(body: bytes))
        PeekStubProtocol.register(base + "lists.json.sig", .ok(body: rig.sign(bytes)))
        let result = await rig.updater(owner: "acme-net2", repo: "kit").check()
        #expect(Self.refusal(result) == .NotNewer(installed: 100, offered: 100))
        #expect(ListsAboutText.sentence(for: result) == "No newer lists.")
    }

    @Test func listsOversizeDownloadIsCutOffAndRefused() async {
        let rig = ListsTestRig()
        let base = "https://raw.githubusercontent.com/acme-net3/kit/lists/"
        PeekStubProtocol.register(base + "lists.json", .ok(body: Data(count: ListsBundle.maxBytes + 4096)))
        let result = await rig.updater(owner: "acme-net3", repo: "kit").check()
        #expect(Self.refusal(result) == .TooLarge)
        #expect(PeekStubProtocol.requests(to: base + "lists.json.sig").isEmpty)
    }

    @Test func listsMissingFilesAndRedirectsAreUnreachable() async {
        let rig = ListsTestRig()
        let base = "https://raw.githubusercontent.com/acme-net4/kit/lists/"
        PeekStubProtocol.register(base + "lists.json", .ok(status: 404))
        #expect(await rig.updater(owner: "acme-net4", repo: "kit").check() == .failed(.unreachable))
        let other = "https://raw.githubusercontent.com/acme-net5/kit/lists/"
        PeekStubProtocol.register(other + "lists.json", .redirect(location: "https://elsewhere.example.com/lists.json"))
        #expect(await rig.updater(owner: "acme-net5", repo: "kit").check() == .failed(.unreachable))
        #expect(PeekStubProtocol.requests(to: "https://elsewhere.example.com/lists.json").isEmpty)
        let down = "https://raw.githubusercontent.com/acme-net6/kit/lists/"
        PeekStubProtocol.register(down + "lists.json", .fail(.notConnectedToInternet))
        #expect(await rig.updater(owner: "acme-net6", repo: "kit").check() == .failed(.unreachable))
        #expect(rig.appliedCalls.isEmpty)
    }

    @Test func listsMissingSignatureFileKeepsTheCurrentLists() async {
        let rig = ListsTestRig()
        let base = "https://raw.githubusercontent.com/acme-net7/kit/lists/"
        PeekStubProtocol.register(base + "lists.json", .ok(body: ListsTestRig.bytes(ListsTestRig.bundle(version: 102))))
        PeekStubProtocol.register(base + "lists.json.sig", .ok(status: 404))
        #expect(await rig.updater(owner: "acme-net7", repo: "kit").check() == .failed(.unreachable))
        #expect(rig.store.read() == nil)
    }

    // MARK: Reset

    @Test func listsResetDeletesTheDownloadAndReloads() throws {
        let rig = ListsTestRig()
        try rig.store.write(Data("saved".utf8))
        rig.updater().resetToBundled()
        #expect(rig.store.read() == nil)
        #expect(rig.appliedCalls == [nil])
    }

    @Test func listsEverySentenceIsPlainAndNeverSaysSafe() {
        let failures: [ListsUpdateFailure] = [.unreachable, .saveFailed, .refused(.TooLarge), .refused(.BadSignature),
            .refused(.Unreadable), .refused(.UnknownSchema(schema: 2)), .refused(.NotNewer(installed: 2, offered: 1)), .refused(.BadShape(file: "brands"))]
        let sentences = failures.map { ListsAboutText.sentence(for: $0) }
        #expect(Set(sentences).count == sentences.count)
        for sentence in sentences {
            #expect(sentence.hasSuffix(".") && !sentence.lowercased().contains("safe"))
        }
    }
}
