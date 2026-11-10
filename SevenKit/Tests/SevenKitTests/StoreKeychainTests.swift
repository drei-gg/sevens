import Foundation
import Testing
@testable import SevenKit

/// Whether the Keychain works in this test process (an unhosted simulator bundle may lack it).
private let keychainWorks: Bool = {
    let probe = KeychainTrialStartStore(service: "seven.tests.probe.\(UUID().uuidString)")
    defer { probe.erase() }
    return probe.writeOnce(Date(timeIntervalSince1970: 1)) != nil
}()

@Suite struct StoreKeychainTests {
    @Test(.enabled(if: keychainWorks, "Keychain unavailable in this test process"))
    func writeOnceKeepsTheFirstDate() {
        let keep = KeychainTrialStartStore(service: "seven.tests.\(UUID().uuidString)")
        defer { keep.erase() }
        #expect(keep.read() == nil)
        let first = Date(timeIntervalSince1970: 1_800_000_000)
        #expect(keep.writeOnce(first) == first)
        #expect(keep.writeOnce(first.addingTimeInterval(500)) == first)
        #expect(keep.read() == first)
    }

    @Test func memoryStoreKeepsTheFirstDate() {
        let keep = MemoryTrialStartStore()
        let first = Date(timeIntervalSince1970: 5)
        #expect(keep.writeOnce(first) == first)
        #expect(keep.writeOnce(Date(timeIntervalSince1970: 9)) == first)
    }
}
