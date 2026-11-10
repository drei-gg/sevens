import Foundation
import Testing
@testable import QRCore

/// "Bundled or downloaded, whichever is newer" (SPEC §4.3), and the saved file.
@Suite struct ListsLoadTests {
    static let shipped = BundledData.load()

    private func bytes(version: Int, built: String = "2027-01-02") throws -> Data {
        try ListsBundleSupport.bytes(try ListsBundleSupport.bundle(version: version, built: built))
    }

    @Test func listsNoDownloadGivesTheBundledLists() {
        #expect(BundledData.load(downloaded: nil).lists == Self.shipped.lists)
        #expect(BundledData.bundledListsVersion() == Self.shipped.lists)
    }

    @Test func listsNewerDownloadWins() throws {
        let data = BundledData.load(downloaded: try bytes(version: Self.shipped.lists.version + 5, built: "2027-03-04"))
        #expect(data.lists.version == Self.shipped.lists.version + 5)
        #expect(data.lists.built == "2027-03-04")
    }

    @Test func listsBundledNewerOrEqualWins() throws {
        #expect(BundledData.load(downloaded: try bytes(version: Self.shipped.lists.version)).lists == Self.shipped.lists)
        #expect(BundledData.load(downloaded: try bytes(version: Self.shipped.lists.version - 1)).lists == Self.shipped.lists)
    }

    @Test func listsBadDownloadsGiveTheBundledLists() throws {
        var bad = try ListsBundleSupport.bundle(version: Self.shipped.lists.version + 1)
        bad.files.brands = []
        #expect(BundledData.load(downloaded: try ListsBundleSupport.bytes(bad)).lists == Self.shipped.lists)
        bad = try ListsBundleSupport.bundle(version: Self.shipped.lists.version + 1)
        bad.schema = 9
        #expect(BundledData.load(downloaded: try ListsBundleSupport.bytes(bad)).lists == Self.shipped.lists)
        #expect(BundledData.load(downloaded: Data("not json".utf8)).lists == Self.shipped.lists)
        #expect(BundledData.load(downloaded: Data()).lists == Self.shipped.lists)
        #expect(BundledData.load(downloaded: Data(count: ListsBundle.maxBytes + 1)).lists == Self.shipped.lists)
    }

    @Test func listsStoreWritesReadsAndRemoves() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("lists-store-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = ListsStore(directory: dir)
        #expect(store.read() == nil)
        try store.write(Data("one".utf8))
        #expect(store.read() == Data("one".utf8))
        try store.write(Data("two".utf8))
        #expect(store.read() == Data("two".utf8))
        store.remove()
        #expect(store.read() == nil)
        store.remove()   // nothing saved: no problem
    }

    @Test func listsStoreIgnoresAnOversizeFile() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("lists-store-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = ListsStore(directory: dir)
        try store.write(Data(count: ListsBundle.maxBytes + 1))
        #expect(store.read() == nil)
    }

    /// The reload swaps `shared` and a reset swaps it back. The bundle is the shipped files, so other suites reading `shared` meanwhile see the same lists.
    @Test func listsReloadSwapsTheSharedLists() throws {
        defer { BundledData.reload(downloaded: nil) }
        BundledData.reload(downloaded: try bytes(version: Self.shipped.lists.version + 7))
        #expect(BundledData.shared.lists.version == Self.shipped.lists.version + 7)
        BundledData.reload(downloaded: nil)
        #expect(BundledData.shared.lists == Self.shipped.lists)
    }

    /// Set by `scripts/refresh-lists`: the `lists.json` it just wrote must validate and load.
    @Test func listsCandidateBundleFromTheScriptIsValid() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<3 { url.deleteLastPathComponent() }   // Tests/QRCoreTests/<file> → Kit
        url.appendPathComponent(".build/lists-candidate.json")
        guard let raw = try? Data(contentsOf: url) else { return }   // no candidate: nothing to check
        #expect(raw.count <= ListsBundle.maxBytes)
        let bundle = try JSONDecoder().decode(ListsBundle.self, from: raw)
        try BundledData.validate(bundle)
        #expect(BundledData(bundle: bundle, base: Self.shipped).lists == bundle.header)
    }
}
