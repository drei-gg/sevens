import Foundation
import Testing
@testable import VaultCore

@Test func pinValidationPreservesLeadingZerosAndRejectsNonASCIIDigits() throws {
    try VaultCredentialKind.pin.validate("00123456")
    for invalid in ["1234567", "１２３４５６７８", "1234567a", "1234 5678"] {
        #expect(throws: (any Error).self) { try VaultCredentialKind.pin.validate(invalid) }
    }
    #expect(VaultCredentialKind.pin.material("00123456") != VaultCredentialKind.passphrase.material("00123456"))
}
@Test func patternCanonicalizationIsIndependentOfSkippedGestureSamples() throws {
    var sparse: [Int] = []; for dot in [0, 4, 24] { PatternPath.append(dot, to: &sparse) }
    #expect(sparse == [0, 1, 2, 3, 4, 9, 14, 19, 24])
    #expect(PatternPath.decode(PatternPath.encode(sparse)) == sparse)
    #expect(PatternPath.decode("0,4,24") == nil)
    #expect(PatternPath.decode("0,1,1") == nil)
    #expect(PatternPath.decode("00,1") == nil)
    #expect(PatternPath.decode("0,25") == nil)
    try VaultCredentialKind.pattern.validate(PatternPath.encode(sparse))
    #expect(throws: VaultError.weakPattern) { try VaultCredentialKind.pattern.validate("0,1,2") }
}
