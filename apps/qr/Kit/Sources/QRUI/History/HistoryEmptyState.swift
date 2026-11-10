import Foundation

/// Which body the History screen shows (SPEC §10). Decided by the number of saved items, not by
/// the current search: a search with no match is a different message.
enum HistoryEmptyState: Equatable {
    case list        // there are items to show
    case readOnly    // the saved file is unreadable or from a newer version
    case historyOff  // Keep history is off and nothing is saved
    case locked      // the trial is over and nothing is saved
    case noItems     // nothing yet

    static func resolve(itemCount: Int, keepHistory: Bool, locked: Bool, readOnly: Bool) -> HistoryEmptyState {
        if readOnly { return .readOnly }
        if itemCount > 0 { return .list }
        if !keepHistory { return .historyOff }
        if locked { return .locked }
        return .noItems
    }

    /// The "Saving new scans needs the unlock" note above a non-empty list.
    static func showsLockedNote(itemCount: Int, keepHistory: Bool, locked: Bool, readOnly: Bool) -> Bool {
        itemCount > 0 && locked && keepHistory && !readOnly
    }
}
