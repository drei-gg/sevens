import Foundation
import Testing
@testable import VaultCore

@Test func rejectsUnsafeNames() {
    for name in ["", " ", "..", "../file", "a/b", "a\\b", "bad\0file", String(repeating: "a", count: 201)] {
        #expect(throws: (any Error).self) { try VaultLibrary.validateName(name) }
    }
    #expect(throws: Never.self) { try VaultLibrary.validateName("Travel & receipts.pdf") }
}
@Test func rejectsFolderCyclesAndDanglingParents() {
    var value = VaultLibrary(name: "Private")
    let first = UUID(), second = UUID()
    value.folders = [VaultFolder(id: first, name: "A", parent: second), VaultFolder(id: second, name: "B", parent: first)]
    #expect(throws: (any Error).self) { try value.validate() }
    value.folders = [VaultFolder(name: "Missing", parent: UUID())]
    #expect(throws: (any Error).self) { try value.validate() }
}
@Test func sortSearchAndRoundtrip() throws {
    var value = VaultLibrary(name: "Travel")
    let a = VaultItem(name: "Boarding pass.pdf", type: "com.adobe.pdf", bytes: 900, digest: String(repeating: "a", count: 64), order: 1)
    let b = VaultItem(name: "Passport.png", type: "public.png", bytes: 300, digest: String(repeating: "b", count: 64), order: 0)
    value.items = [a, b]; value.sort = .largest; value.grid = true
    try value.validate()
    #expect(VaultOrder.largest.sorted(value.items).first == a)
    #expect(VaultOrder.manual.sorted(value.items).first == b)
    #expect(a.matches("BOARDING"))
    #expect(try JSONDecoder().decode(VaultLibrary.self, from: JSONEncoder().encode(value)) == value)
}
