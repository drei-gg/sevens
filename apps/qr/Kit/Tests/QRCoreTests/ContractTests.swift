import Foundation
import Testing
@testable import QRCore

// Phase 0 owns this file.

@Test func verdictRule() {
    let a = Analyzer(data: BundledData(publicSuffixes: PublicSuffixList(text: "")))
    #expect(a.level(for: []) == .normal)
    #expect(a.level(for: [Signal(.assumedHTTPS), Signal(.ageUnknown)]) == .normal)
    #expect(a.level(for: [Signal(.plainHTTP)]) == .careful)
    #expect(a.level(for: [Signal(.plainHTTP), Signal(.ipHost)]) == .careful)
    #expect(a.level(for: [Signal(.plainHTTP), Signal(.ipHost), Signal(.riskyTLD)]) == .likelyScam)
    #expect(a.level(for: [Signal(.userinfoTrick)]) == .likelyScam)
}

@Test func signalOrderIsStrongWarningInfoThenDeclaration() {
    let s = [Signal(.assumedHTTPS), Signal(.plainHTTP), Signal(.userinfoTrick), Signal(.ipHost), Signal(.dangerousScheme)]
    #expect(s.inStableOrder().map(\.id) == [.dangerousScheme, .userinfoTrick, .ipHost, .plainHTTP, .assumedHTTPS])
}

@Test func everySignalHasASeverity() {
    let counts = Dictionary(grouping: SignalID.allCases, by: \.severity).mapValues(\.count)
    #expect(counts[.strong] == 9)
    #expect(counts[.warning] == 17)
    #expect(counts[.info] == 5)
}

@Test func payloadFieldsAreFlat() {
    let p = Payload.payment(PaymentInfo(format: .emv, payeeName: "Ana", isDynamic: false, checksum: .valid, extra: ["networkID": "x"]))
    #expect(p.typeID == "payment")
    #expect(p.fields["payeeName"] == "Ana")
    #expect(p.fields["isDynamic"] == "false")
    #expect(p.fields["extra.networkID"] == "x")
    #expect(p.fields["amount"] == nil)
    let e = EventInfo(start: Date(timeIntervalSince1970: 0), isAllDay: true)
    #expect(Payload.event(e).fields["start"] == "1970-01-01")
}

@Test func bitMatrixBounds() {
    let m = BitMatrix(width: 2, height: 1, bits: [true, false])
    #expect(m[0, 0] && !m[1, 0] && !m[5, 5])
    #expect(BitMatrix(width: 2, height: 2, bits: [true]).width == 0)
}

@Test func bundledDataLoadsWithoutFiles() {
    _ = BundledData.load()
}
