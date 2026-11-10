import Foundation

/// Note editing rules for the detail screen. The store trims and clears blank notes itself;
/// this only decides whether "Save note" has anything to save.
enum HistoryNote {
    static func normalized(_ text: String?) -> String {
        (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func isChanged(draft: String, saved: String?) -> Bool {
        normalized(draft) != normalized(saved)
    }
}
