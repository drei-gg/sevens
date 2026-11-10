import Foundation
import QRCore

/// The sentences and dates of the About lists rows (SPEC §4.3). Pure, so tests can read them.
enum ListsAboutText {
    /// A list older than this gets the quiet extra line.
    static let oldAfterMonths = 6

    /// "2026-11-10" as that calendar day (noon UTC, so no time zone moves it), or nil.
    static func date(built: String) -> Date? {
        let parts = built.split(separator: "-", omittingEmptySubsequences: false).map { Int($0) }
        guard parts.count == 3, let y = parts[0], let m = parts[1], let d = parts[2] else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        return calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 12))
    }

    /// "10 November 2026", in the person's language and region.
    static func dateText(built: String, locale: Locale = .current) -> String? {
        guard let date = date(built: built) else { return nil }
        var style = Date.FormatStyle(date: .long, time: .omitted, locale: locale, timeZone: TimeZone(identifier: "UTC") ?? .current)
        style.calendar = Calendar(identifier: .gregorian)
        return date.formatted(style)
    }

    static func title(for lists: ListsVersion, locale: Locale = .current) -> String {
        guard let text = dateText(built: lists.built, locale: locale) else {
            return String(localized: "lists.title.undated", defaultValue: "Scam lists", bundle: .module)
        }
        return String(localized: "lists.title", defaultValue: "Scam lists from \(text)", bundle: .module)
    }

    /// True when `built` is more than six months before `now`. An unreadable date counts as old.
    static func isOld(built: String, now: Date = Date()) -> Bool {
        guard let date = date(built: built) else { return true }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        guard let limit = calendar.date(byAdding: .month, value: oldAfterMonths, to: date) else { return true }
        return now > limit
    }

    static var oldLine: String {
        String(localized: "lists.old", defaultValue: "These lists are more than six months old.", bundle: .module)
    }

    static var checkButton: String {
        String(localized: "lists.check", defaultValue: "Check for newer lists", bundle: .module)
    }

    static var checking: String {
        String(localized: "lists.checking", defaultValue: "Checking…", bundle: .module)
    }

    static var resetButton: String {
        String(localized: "lists.reset", defaultValue: "Use the lists that came with the app", bundle: .module)
    }

    static var resetDone: String {
        String(localized: "lists.reset.done", defaultValue: "Using the lists that came with the app.", bundle: .module)
    }

    /// One plain sentence per outcome.
    static func sentence(for result: ListsUpdateResult, locale: Locale = .current) -> String {
        switch result {
        case .updated(let lists):
            guard let text = dateText(built: lists.built, locale: locale) else {
                return String(localized: "lists.result.updatedUndated", defaultValue: "Updated to newer lists.", bundle: .module)
            }
            return String(localized: "lists.result.updated", defaultValue: "Updated to lists from \(text).", bundle: .module)
        case .failed(let failure):
            return sentence(for: failure)
        }
    }

    static func sentence(for failure: ListsUpdateFailure) -> String {
        switch failure {
        case .unreachable:
            return String(localized: "lists.result.unreachable", defaultValue: "Couldn't reach GitHub. Your lists are unchanged.", bundle: .module)
        case .saveFailed:
            return String(localized: "lists.result.saveFailed", defaultValue: "Couldn't save the newer lists. Your lists are unchanged.", bundle: .module)
        case .refused(let reason):
            switch reason {
            case .notNewer:
                return String(localized: "lists.result.none", defaultValue: "No newer lists.", bundle: .module)
            case .tooLarge:
                return String(localized: "lists.result.tooLarge", defaultValue: "The download was larger than expected, so we kept your lists.", bundle: .module)
            case .badSignature:
                return String(localized: "lists.result.signature", defaultValue: "The download didn't carry a valid signature, so we kept your lists.", bundle: .module)
            case .unreadable:
                return String(localized: "lists.result.unreadable", defaultValue: "The download couldn't be read, so we kept your lists.", bundle: .module)
            case .unknownSchema:
                return String(localized: "lists.result.schema", defaultValue: "These lists are for a newer version of the app, so we kept yours.", bundle: .module)
            case .badShape:
                return String(localized: "lists.result.shape", defaultValue: "The download didn't pass our checks, so we kept your lists.", bundle: .module)
            }
        }
    }
}
