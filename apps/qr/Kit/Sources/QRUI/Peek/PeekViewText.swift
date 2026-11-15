import Foundation
import QRCore

/// The wording of a Peek result, as pure functions (SPEC §6).
enum PeekViewText {
    /// "Registered 12 days ago" / "Age unknown".
    static func age(days: Int?) -> String {
        guard let days, days >= 0 else { return String(localized: "peek.age.unknown", defaultValue: "Age unknown", bundle: .module) }
        switch days {
        case 0: return String(localized: "peek.age.today", defaultValue: "Registered today", bundle: .module)
        case 1: return String(localized: "peek.age.one", defaultValue: "Registered 1 day ago", bundle: .module)
        default: return String(localized: "peek.age.other", defaultValue: "Registered \(days) days ago", bundle: .module)
        }
    }

    /// TLS version, or why there is none.
    static func connection(_ capture: PeekCapture) -> String {
        if capture.stoppedBecause == .tlsFailure {
            return String(localized: "peek.connection.tlsFailure", defaultValue: "The site’s certificate was rejected", bundle: .module)
        }
        if let last = capture.hops.last, last.status == nil, last.url.lowercased().hasPrefix("http://") {
            return String(localized: "peek.connection.http", defaultValue: "Not fetched: plain http", bundle: .module)
        }
        if let version = capture.tlsVersion, !version.isEmpty {
            return String(localized: "peek.connection.tls", defaultValue: "Encrypted (\(version))", bundle: .module)
        }
        return String(localized: "peek.connection.unknown", defaultValue: "Not known", bundle: .module)
    }

    /// "a password, a one-time code or PIN" from the `asksForSecret` signals; nil when none.
    static func asks(_ signals: [Signal]) -> String? {
        var kinds: [String] = []
        for signal in signals where signal.id == .asksForSecret || signal.id == .secretFormBrandMismatch {
            let kind = signal.args["kind"] ?? ""
            let word: String
            switch kind {
            case "password": word = String(localized: "peek.asks.password", defaultValue: "a password", bundle: .module)
            case "code": word = String(localized: "peek.asks.code", defaultValue: "a one-time code or PIN", bundle: .module)
            case "card": word = String(localized: "peek.asks.card", defaultValue: "card details", bundle: .module)
            default: word = String(localized: "peek.asks.other", defaultValue: "private details", bundle: .module)
            }
            if !kinds.contains(word) { kinds.append(word) }
        }
        return kinds.isEmpty ? nil : kinds.joined(separator: ", ")
    }

    /// Whether the page was read (so "asks for nothing" means something).
    static func pageWasRead(_ capture: PeekCapture) -> Bool {
        capture.stoppedBecause == .finished && capture.hops.last?.status != nil
    }
}

/// One hop in the numbered list.
struct PeekHopRow: Hashable {
    let number: Int
    let domain: String
    let url: String
    let status: Int?
    let level: Level?

    /// `analyses` (from the finished report) wins; otherwise the hop is parsed on the spot so
    /// hops can show before the age lookup finishes.
    static func rows(hops: [PeekHop], analyses: [Analysis], data: BundledData) -> [PeekHopRow] {
        hops.enumerated().map { index, hop in
            var link: LinkInfo?
            var level: Level?
            if index < analyses.count {
                level = analyses[index].level
                if case .link(let info) = analyses[index].payload { link = info }
            }
            if link == nil { link = WebAddress.parse(hop.url, psl: data.publicSuffixes)?.link }
            let domain = link.map(ResultLinkDisplay.headline) ?? hop.url
            return PeekHopRow(number: index + 1, domain: domain, url: hop.url, status: hop.status.map(Int.init), level: level)
        }
    }
}
