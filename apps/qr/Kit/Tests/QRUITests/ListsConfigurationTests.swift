import CryptoKit
import Foundation
import Testing
@testable import QRUI

/// The repository and keys come from the brand's configuration; without a key there is no button.
@Suite struct ListsConfigurationTests {
    static let key = ListsTestRig().publicHex

    @Test func listsNoKeyMeansNoConfiguration() {
        #expect(ListsConfiguration(repositoryURL: "https://github.com/acme/kit", keys: [nil, nil]) == nil)
        #expect(ListsConfiguration(repositoryURL: "https://github.com/acme/kit", keys: ["", ""]) == nil)
        #expect(ListsConfiguration(repositoryURL: "https://github.com/acme/kit", keys: ["abcd", String(repeating: "z", count: 64)]) == nil)
    }

    @Test func listsNoRepositoryMeansNoConfiguration() {
        #expect(ListsConfiguration(repositoryURL: nil, keys: [Self.key]) == nil)
        #expect(ListsConfiguration(repositoryURL: "", keys: [Self.key]) == nil)
        #expect(ListsConfiguration(repositoryURL: "http://github.com/acme/kit", keys: [Self.key]) == nil)
        #expect(ListsConfiguration(repositoryURL: "https://example.com/acme/kit", keys: [Self.key]) == nil)
        #expect(ListsConfiguration(repositoryURL: "https://github.com/acme", keys: [Self.key]) == nil)
        #expect(ListsConfiguration(repositoryURL: "https://github.com/acme/kit/extra", keys: [Self.key]) == nil)
        #expect(ListsConfiguration(repositoryURL: "https://github.com/../kit", keys: [Self.key]) == nil)
        #expect(ListsConfiguration(repositoryURL: "https://github.com/ac me/kit", keys: [Self.key]) == nil)
    }

    @Test func listsRawAddressIsBuiltFromTheRepository() {
        for text in ["https://github.com/acme/kit", "https://github.com/acme/kit/", "https://github.com/acme/kit.git", " https://GitHub.com/acme/kit\n"] {
            let config = ListsConfiguration(repositoryURL: text, keys: [Self.key])
            #expect(config?.fileURL("lists.json")?.absoluteString == "https://raw.githubusercontent.com/acme/kit/lists/lists.json")
            #expect(config?.fileURL("lists.json.sig")?.absoluteString == "https://raw.githubusercontent.com/acme/kit/lists/lists.json.sig")
        }
    }

    @Test func listsTwoKeysAreKeptAndBadOnesIgnored() {
        let next = ListsTestRig().publicHex
        let both = ListsConfiguration(repositoryURL: "https://github.com/acme/kit", keys: [Self.key.uppercased(), next])
        #expect(both?.keys.count == 2)
        let one = ListsConfiguration(repositoryURL: "https://github.com/acme/kit", keys: [Self.key, "nonsense"])
        #expect(one?.keys.count == 1)
        let same = ListsConfiguration(repositoryURL: "https://github.com/acme/kit", keys: [Self.key, Self.key])
        #expect(same?.keys.count == 1)
    }

    @Test func listsBuildWithoutBrandSettingsHidesTheButton() async throws {
        let bundle = Bundle(for: ListsConfigurationProbe.self)
        #expect(SettingsInfo.listsConfiguration(bundle: bundle) == nil)
        #expect(ListsUpdater.configured(bundle: bundle) == nil)
        let model = await ListsAboutModel(updater: ListsUpdater.configured(bundle: bundle))
        #expect(await model.canCheck == false)
    }
}

private final class ListsConfigurationProbe {}
