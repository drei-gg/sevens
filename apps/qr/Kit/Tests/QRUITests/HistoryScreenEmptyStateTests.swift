import Testing
@testable import QRUI

@Suite struct HistoryScreenEmptyStateTests {
    private func state(_ count: Int, keep: Bool = true, locked: Bool = false, readOnly: Bool = false) -> HistoryEmptyState {
        HistoryEmptyState.resolve(itemCount: count, keepHistory: keep, locked: locked, readOnly: readOnly)
    }

    @Test func itemsAlwaysShowTheListEvenWhenLockedOrOff() {
        #expect(state(3) == .list)
        #expect(state(3, locked: true) == .list)
        #expect(state(3, keep: false) == .list)
    }

    @Test func emptyStatesAreTheRightOnes() {
        #expect(state(0) == .noItems)
        #expect(state(0, keep: false) == .historyOff)
        #expect(state(0, locked: true) == .locked)
        #expect(state(0, keep: false, locked: true) == .historyOff)
    }

    @Test func readOnlyWinsOverEverything() {
        #expect(state(0, keep: false, locked: true, readOnly: true) == .readOnly)
        #expect(state(2, readOnly: true) == .readOnly)
    }

    @Test func lockedNoteOnlyAboveANonEmptyListThatWouldOtherwiseSave() {
        #expect(HistoryEmptyState.showsLockedNote(itemCount: 2, keepHistory: true, locked: true, readOnly: false))
        #expect(!HistoryEmptyState.showsLockedNote(itemCount: 0, keepHistory: true, locked: true, readOnly: false))
        #expect(!HistoryEmptyState.showsLockedNote(itemCount: 2, keepHistory: false, locked: true, readOnly: false))
        #expect(!HistoryEmptyState.showsLockedNote(itemCount: 2, keepHistory: true, locked: false, readOnly: false))
    }
}
