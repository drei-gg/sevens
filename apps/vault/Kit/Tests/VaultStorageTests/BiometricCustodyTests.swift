import Testing
@testable import VaultStorage

@Test func enrollingRemovesEveryEarlierVaultsItem() {
    #expect(BiometricCustody.enrolling("B", stored: "A", items: ["A"]) == ["A"])
    #expect(BiometricCustody.enrolling("B", stored: "A", items: ["A", "B", "C"]) == ["A", "C"])
    #expect(BiometricCustody.enrolling("A", stored: nil, items: []) == [])
}

@Test func removingUsesTheStoredIDNotTheOpenVault() {
    // Vault B is open, but A holds the biometric key: A's item goes.
    #expect(BiometricCustody.removing(stored: "A", items: ["A"]) == ["A"])
    #expect(BiometricCustody.removing(stored: nil, items: ["X"]) == ["X"])
}

@Test func launchCleanupDropsOrphans() {
    let kept = BiometricCustody.cleanup(stored: "A", vaults: ["A", "B"], items: ["A", "C"])
    #expect(kept.keep); #expect(kept.remove == ["C"])
    let gone = BiometricCustody.cleanup(stored: "A", vaults: ["B"], items: ["A"])
    #expect(!gone.keep); #expect(gone.remove == ["A"])
    let none = BiometricCustody.cleanup(stored: nil, vaults: ["B"], items: ["B"])
    #expect(!none.keep); #expect(none.remove == ["B"])
}
