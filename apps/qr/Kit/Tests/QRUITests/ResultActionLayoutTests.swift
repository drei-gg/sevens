import Testing
import QRCore
@testable import QRUI

/// The pinned pair and the chips (polish pass, 13 November 2026).
@Suite struct ResultActionLayoutTests {
    @Test func linkLeadsWithOpenThenPeek() {
        let layout = ResultActionPlan.layout([.peek, .open, .copy, .share, .makeCode], level: .normal)
        #expect(layout == ResultActionLayout(primary: .open, secondary: .peek, chips: [.copy, .share, .makeCode]))
        #expect(ResultActionPlan.layout([.peek, .open, .copy], level: .careful).primary == .open)
    }

    @Test func likelyScamLeadsWithPeekAndMovesOpenToTheChips() {
        let layout = ResultActionPlan.layout([.peek, .open, .copy, .share, .makeCode], level: .likelyScam)
        #expect(layout == ResultActionLayout(primary: .peek, secondary: nil, chips: [.open, .copy, .share, .makeCode]))
    }

    @Test func payloadActionLeadsAndCopyStaysAChip() {
        #expect(ResultActionPlan.layout([.addContact, .copy, .share, .makeCode], level: .normal)
            == ResultActionLayout(primary: .addContact, secondary: nil, chips: [.copy, .share, .makeCode]))
        #expect(ResultActionPlan.layout([.joinWiFi, .copyPassword, .makeCode], level: .careful)
            == ResultActionLayout(primary: .joinWiFi, secondary: .copyPassword, chips: [.makeCode]))
    }

    @Test func withoutAnActionOfItsOwnCopyLeads() {
        #expect(ResultActionPlan.layout([.copy, .share, .makeCode], level: .normal)
            == ResultActionLayout(primary: .copy, secondary: nil, chips: [.share, .makeCode]))
        #expect(ResultActionPlan.layout([.copyDetails], level: .normal)
            == ResultActionLayout(primary: .copyDetails, secondary: nil, chips: []))
        #expect(ResultActionPlan.layout([], level: .normal) == ResultActionLayout(primary: nil, secondary: nil, chips: []))
        // An open Wi-Fi network in a build that can't join: nothing to lead with.
        #expect(ResultActionPlan.layout([.makeCode], level: .careful) == ResultActionLayout(primary: nil, secondary: nil, chips: [.makeCode]))
    }

    @Test func everyActionIsKeptOnce() {
        let all: [ResultAction] = [.peek, .open, .joinWiFi, .copyPassword, .addContact, .copy, .share, .makeCode]
        for level in [Level.normal, .careful, .likelyScam] {
            let layout = ResultActionPlan.layout(all, level: level)
            let shown = [layout.primary, layout.secondary].compactMap { $0 } + layout.chips
            #expect(Set(shown) == Set(all) && shown.count == all.count)
        }
    }
}
