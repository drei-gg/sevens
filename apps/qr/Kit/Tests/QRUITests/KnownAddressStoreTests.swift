import Foundation
import Testing
import QRCore
import SevenKit
import SevenKitCore
@testable import QRUI

private func tempDirectory() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("qr-known-\(UUID().uuidString)", isDirectory: true)
}

@Suite struct KnownAddressStoreTests {
    @Test func persistsAcrossInstancesBesideHistoryFile() async throws {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let first = KnownAddressStore(directory: dir)
        #expect(await first.add("Example.com", at: Date(timeIntervalSince1970: 5)) != nil)
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("known.v1.json").path))
        let second = KnownAddressStore(directory: dir)
        #expect(await second.addresses().contains("example.com"))
        _ = await second.remove("example.com")
        #expect(await KnownAddressStore(directory: dir).addresses().isEmpty)
    }

    @Test func unreadableFileIsNeverOverwritten() async throws {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("known.v1.json")
        let future = Data(#"{"schema":9,"entries":[]}"#.utf8)
        try future.write(to: url)
        let store = KnownAddressStore(directory: dir)
        #expect(await store.addresses().isEmpty)
        #expect(await store.isReadOnly)
        #expect(await store.add("a.example") == nil)
        #expect(try Data(contentsOf: url) == future)
    }

    @Test func failedWriteChangesNothing() async {
        let store = KnownAddressStore(directory: tempDirectory(), writer: { _, _ in throw CocoaError(.fileWriteUnknown) })
        #expect(await store.add("a.example") == nil)
        #expect(await store.addresses().isEmpty)
    }

    @Test func inMemoryStoreSeedsAndForgetsAll() async {
        var seed = KnownAddresses()
        seed.add("a.example", at: Date())
        let store = KnownAddressStore(directory: nil, seed: seed)
        #expect(await store.addresses().contains("a.example"))
        #expect(await store.removeAll()?.isEmpty == true)
    }
}

@MainActor
@Suite struct KnownAppModelTests {
    private func model(known: KnownAddressStore = KnownAddressStore(directory: nil)) async -> AppModel {
        let suite = "qr.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        let store = Store(config: SevenConfig(trialProductID: "t", unlockProductID: "u"),
                          source: FixedSource(facts: EntitlementFacts()), trialStartStore: MemoryTrialStartStore(),
                          defaults: defaults)
        let model = AppModel(store: store, history: HistoryStore(directory: nil), settings: AppSettings(defaults: defaults),
                             known: known)
        await model.start()
        return model
    }

    @Test func knowingAnAddressChangesLaterAnalyses() async {
        let model = await model()
        let code = ScannedCode(text: "https://bit.ly/abc")
        let before = await model.analysis(of: code)
        #expect(before.level == .careful)
        #expect(model.knownAddresses.offer(for: before) == "bit.ly")
        #expect(await model.knowAddress("bit.ly"))
        let after = await model.analysis(of: code)
        #expect(after.level == .normal)
        #expect(after.signals.map(\.id) == [.knownByYou])
        #expect(model.knownAddresses.offer(for: after) == nil)
        #expect(await model.forgetAddress("bit.ly"))
        #expect(await model.analysis(of: code).level == .careful)
    }

    @Test func forgetAllAndLoadAtStart() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let first = await model(known: KnownAddressStore(directory: dir))
        _ = await first.knowAddress("a.example")
        _ = await first.knowAddress("b.example")
        let second = await model(known: KnownAddressStore(directory: dir))
        #expect(second.knownAddresses.entries.count == 2)
        #expect(await second.forgetAllAddresses())
        #expect(second.knownAddresses.isEmpty)
    }

    @Test func historyJSONExportIncludesKnownAddresses() async throws {
        let model = await model()
        _ = await model.knowAddress("a.example")
        let data = try await model.exportHistoryJSON()
        #expect(HistoryExport.knownAddresses(in: data).contains("a.example"))
        #expect(HistoryFile.decode(data) == .items([]))
    }
}
