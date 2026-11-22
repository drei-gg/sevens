import Foundation
import Testing
import VaultCore

// The rules themselves are tested in the crate (apps/vault/core/tests). These check that the façade
// only converts: names, Int <-> Int32, sentences and conformances.

@Test func patternPathConvertsDotIndexes() throws {
    var path: [Int] = []; for dot in [0, 4, 24] { PatternPath.append(dot, to: &path) }
    #expect(path == [0, 1, 2, 3, 4, 9, 14, 19, 24])
    #expect(PatternPath.decode(PatternPath.encode(path)) == path)
    #expect(PatternPath.decode("0,4,24") == nil)
    var untouched = [3]; PatternPath.append(Int.max, to: &untouched)
    #expect(untouched == [3])
    try VaultCredentialKind.pattern.validate(PatternPath.encode(path))
}

@Test func credentialKindKeepsItsSwiftNames() {
    #expect(VaultCredentialKind.allCases == [.passphrase, .pin, .pattern])
    #expect(VaultCredentialKind.allCases.map(\.title) == ["Passphrase", "PIN", "Pattern"])
    #expect(throws: VaultError.WeakPattern) { try VaultCredentialKind.pattern.validate("0,1,2") }
    #expect(VaultCredentialKind.pin.material("00123456") == "Vault/PIN/v1/00123456")
}

@Test func errorsFallBackToTheCoreSentence() {
    // This package's main bundle has no catalog, so the core's English shows.
    #expect(VaultError.Damaged.vaultMessage == "The data could not be verified. The existing vault has not been replaced.")
    #expect(VaultIssue.tooManyPages(maximum: 20).message == "Choose up to 20 pages at a time.")
    #expect(CocoaError(.fileNoSuchFile).vaultMessage == CocoaError(.fileNoSuchFile).localizedDescription)
}

/// The app's String Catalog, read from the source tree (App/Localizable.xcstrings).
private func catalog() throws -> [String: Any] {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../../App/Localizable.xcstrings").standardizedFileURL
    let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
    return json?["strings"] as? [String: Any] ?? [:]
}

/// Every English form of `key` in `strings` (a plain value, or each plural variant).
private func englishForms(_ key: String, in strings: [String: Any]) -> [String] {
    guard let entry = strings[key] as? [String: Any], let localizations = entry["localizations"] as? [String: Any],
          let en = localizations["en"] as? [String: Any] else { return [] }
    if let unit = en["stringUnit"] as? [String: Any], let value = unit["value"] as? String { return [value] }
    let plural = (en["variations"] as? [String: Any])?["plural"] as? [String: Any] ?? [:]
    return plural.values.compactMap { (($0 as? [String: Any])?["stringUnit"] as? [String: Any])?["value"] as? String }
}

@Test func everyCoreCaseHasACatalogSentence() throws {
    let strings = try catalog()
    // The core's case lists are complete (its own test); each case brings its argument count.
    var cases: [(String, Int, String)] = []
    cases += vaultErrorCases().map { ($0.catalogKey, 0, $0.message) }
    cases += recognitionFailureCases().map { ($0.catalogKey, 0, $0.message) }
    cases += vaultIssueCases().map { ($0.catalogKey, $0.arguments.count, $0.message) }
    for (key, arguments, fallback) in cases {
        let forms = englishForms(key, in: strings)
        #expect(!forms.isEmpty, "\(key) is missing from App/Localizable.xcstrings")
        for form in forms {
            #expect(!form.trimmingCharacters(in: .whitespaces).isEmpty, "\(key) is empty")
            #expect(form.components(separatedBy: "%lld").count - 1 == arguments, "\(key) needs \(arguments) %lld")
        }
        #expect(!fallback.isEmpty)
    }
}

@Test func catalogKeysAreDistinct() {
    var keys = vaultErrorCases().map(\.catalogKey)
    keys += recognitionFailureCases().map(\.catalogKey)
    keys += vaultIssueCases().map(\.catalogKey)
    #expect(Set(keys).count == keys.count)
}

@Test func libraryNamesAndOrder() {
    let library = VaultLibrary(name: "Travel")
    #expect(library.version == 1 && library.sort == .newest && !library.grid && library.items.isEmpty)
    var item = VaultItem(id: UUID(), name: "Boarding pass.pdf", contentType: "com.adobe.pdf", bytes: 900,
                         digest: String(repeating: "a", count: 64), created: Date(), folder: nil, favorite: false, deleted: nil, order: 0)
    #expect(item.type == "com.adobe.pdf")
    item.type = "public.data"; #expect(item.contentType == "public.data")
    #expect(item.matches("BOARDING") && item.matches("") && !item.matches("passport"))
    #expect(VaultOrder.allCases.map(\.rawValue) == ["newest", "oldest", "name", "largest", "manual"])
    // A Swift `Date()` is rounded once on its way through the core; after that it is stable.
    let once = VaultOrder.largest.sorted([item])
    #expect(once.map(\.id) == [item.id])
    #expect(VaultOrder.largest.sorted(once) == once)
}

@Test func coreDatesSurviveTheBoundaryAndTheIndex() throws {
    var library = VaultLibrary(name: "Notes")
    let item = libraryImportItem(library: library, name: "a.txt", contentType: "public.plain-text", bytes: 1,
                                 digest: String(repeating: "b", count: 64), folder: nil)
    library = libraryAppendItem(library: library, item: item)
    library = try libraryUpdateItem(library: library, id: item.id, name: nil, favorite: true, trashed: true, now: Date())
    #expect(try libraryDecode(data: libraryEncode(library: library), headerId: library.id) == library)
    #expect(librarySetPreferences(library: library, sort: library.sort, grid: library.grid) == library)
}

@Test func batchRecognitionPlanCrossesTheNativeBoundaryWithoutLosingSelection() throws {
    let first = newVaultItem(name: "First.png", contentType: "public.png", bytes: 12, digest: String(repeating: "a", count: 64), folder: nil, order: 0)
    let second = newVaultItem(name: "Second.pdf", contentType: "com.adobe.pdf", bytes: 24, digest: String(repeating: "b", count: 64), folder: nil, order: 1)
    let plan = recognitionBatchPlan(items: [first, second], selected: [second.id, first.id], supported: [first.id, second.id])
    #expect(plan.issue == nil && plan.items.map(\.id) == [first.id, second.id])
    #expect(plan.maximumItems == 10 && plan.maximumPdfPages == 20)
    #expect(recognitionBatchPlan(items: [first], selected: [first.id], supported: []).items.isEmpty)
    #expect(try recognitionReviewText(text: "café") == "café")
}
