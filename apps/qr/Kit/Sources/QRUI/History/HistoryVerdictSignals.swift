import Foundation
import QRCore

/// History keeps signal IDs only (no args). This rebuilds `Signal`s for display; IDs this version
/// doesn't know (written by a newer one) are skipped. The text then uses SignalText's generic
/// wording for each.
enum HistoryVerdictSignals {
    static func signals(from ids: [String]) -> [Signal] {
        ids.compactMap { SignalID(rawValue: $0) }.map { Signal($0) }.inStableOrder()
    }
}
