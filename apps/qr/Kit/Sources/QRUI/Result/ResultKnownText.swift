import Foundation
import QRCore

/// The words of the "I know this address" question (SPEC §3). Plain, no verdict language.
enum ResultKnownText {
    /// One sentence about why we're asking. The brand comes from the list-based signal; without
    /// one, the sentence names the domain and what we don't know about it.
    static func question(for analysis: Analysis, domain: String) -> String {
        let brand = analysis.signals.lazy
            .filter { KnownAddresses.listBased.contains($0.id) }
            .compactMap { SignalText.arg($0.args, "brand") }
            .first
        if let brand {
            return String(localized: "result.known.question.brand",
                          defaultValue: "This address uses \(brand)'s name, but it isn't one we know.", bundle: .module)
        }
        let name = SignalText.arg(["d": domain], "d") ?? domain
        let ids = Set(analysis.signals.map(\.id))
        if ids.contains(.shortener) {
            return String(localized: "result.known.question.shortener",
                          defaultValue: "\(name) is a link shortener, so we can't tell where it leads.", bundle: .module)
        }
        if ids.contains(.riskyTLD) {
            return String(localized: "result.known.question.ending",
                          defaultValue: "\(name) ends in a domain ending scammers use a lot, and it isn't an address we know.",
                          bundle: .module)
        }
        return String(localized: "result.known.question.plain",
                      defaultValue: "\(name) isn't an address we know.", bundle: .module)
    }
}
