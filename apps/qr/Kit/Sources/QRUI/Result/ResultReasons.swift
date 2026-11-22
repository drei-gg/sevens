import Foundation
import QRCore

/// One reason under a verdict: a plain sentence (SPEC §4.1). Info reasons never change the
/// verdict and are drawn quieter.
struct ResultReason: Hashable, Sendable {
    let sentence: String
    let isInfo: Bool
}

/// Every reason that drives a verdict, as sentences, in the order they are shown: the payload's
/// own, then each link found inside it (named, so a text or contact whose verdict comes from a
/// link says which one), then what Peek found. Identical sentences appear once.
enum ResultReasons {
    /// The payload's own signals, then the warning and strong signals of each link inside it.
    static func rows(for analysis: Analysis) -> [ResultReason] {
        var rows = analysis.signals.map { ResultReason(sentence: SignalText.sentence(for: $0), isInfo: $0.severity == .info) }
        for item in analysis.embedded {
            let domain = ResultEmbeddedList.domain(of: item)
            for signal in item.allSignals where signal.severity != .info {
                rows.append(ResultReason(sentence: inLink(domain, SignalText.sentence(for: signal)), isInfo: false))
            }
        }
        return unique(rows)
    }

    /// Peek's own signals, then the warning and strong signals of each redirect that isn't the
    /// scanned link itself (a hop can raise the verdict without a Peek signal of its own).
    static func peekRows(_ report: PeekReport, original: Analysis) -> [ResultReason] {
        var rows = report.signals.map { ResultReason(sentence: SignalText.sentence(for: $0), isInfo: $0.severity == .info) }
        var scanned: String?
        if case .link(let link) = original.payload { scanned = link.normalized }
        for hop in report.hopAnalyses {
            guard case .link(let link) = hop.payload, link.normalized != scanned else { continue }
            let domain = ResultLinkDisplay.headline(link)
            for signal in hop.allSignals where signal.severity != .info {
                rows.append(ResultReason(sentence: redirect(domain, SignalText.sentence(for: signal)), isInfo: false))
            }
        }
        return unique(rows)
    }

    /// Both lists, as one, without repeats.
    static func rows(for analysis: Analysis, peek report: PeekReport?) -> [ResultReason] {
        unique(rows(for: analysis) + (report.map { peekRows($0, original: analysis) } ?? []))
    }

    static func inLink(_ domain: String, _ sentence: String) -> String {
        String(localized: "result.reason.inLink", defaultValue: "Link to \(domain): \(sentence)", bundle: .module,
               comment: "A reason under the verdict that comes from a link inside a text, contact or event. First: the link’s domain. Second: the reason sentence.")
    }

    static func redirect(_ domain: String, _ sentence: String) -> String {
        String(localized: "result.reason.redirect", defaultValue: "Redirect to \(domain): \(sentence)", bundle: .module,
               comment: "A reason Peek found on a redirect. First: the redirect’s domain. Second: the reason sentence.")
    }

    private static func unique(_ rows: [ResultReason]) -> [ResultReason] {
        var seen = Set<String>()
        return rows.filter { seen.insert($0.sentence).inserted }
    }
}
