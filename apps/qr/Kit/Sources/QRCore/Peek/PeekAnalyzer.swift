import Foundation

// Contract. WP 2.1 owns this file: hops + page → signals (SPEC §6).

public struct PeekAnalyzer: Sendable {
    public let data: BundledData
    let analyzer: Analyzer

    public init(data: BundledData) {
        self.data = data
        self.analyzer = Analyzer(data: data)
    }

    static let downloadExtensions: Set<String> = ["apk", "ipa", "mobileconfig", "exe", "msi", "dmg", "pkg", "zip", "scr", "bat"]

    /// `ageDays` nil means the age is unknown (no RDAP service, 404, no registration event, timeout).
    public func report(original: Analysis, capture: PeekCapture, ageDays: Int?) -> PeekReport {
        let psl = data.publicSuffixes
        let symbology = original.code.symbology
        let hopAnalyses = capture.hops.map { analyzer.analyze(ScannedCode(text: $0.url, symbology: symbology)) }
        let final = capture.hops.last(where: { $0.status != nil }).flatMap { WebAddress.parse($0.url, psl: psl) }
        let finalLink = final?.link

        var signals: [Signal] = []
        switch capture.stoppedBecause {
        case .tooManyRedirects: signals.append(Signal(.tooManyRedirects))
        case .httpTarget: signals.append(Signal(.redirectsToHTTP, Self.lastHopArgs(capture, psl: psl)))
        case .nonWebTarget:
            let scheme = capture.hops.last.flatMap { WebAddress.scheme(of: $0.url) } ?? ""
            signals.append(Signal(.redirectLeavesWeb, scheme.isEmpty ? [:] : ["scheme": scheme]))
        case .tlsFailure: signals.append(Signal(.tlsProblem))
        case .timeout, .networkError: signals.append(Signal(.peekIncomplete))
        case .finished: break
        }

        var title: String?
        if let finalLink {
            if Self.downloads(capture, finalLink) { signals.append(Signal(.downloadsFile)) }
            if let sniff = Self.sniff(capture) {
                title = sniff.title
                signals += pageSignals(sniff, finalLink: finalLink)
            }
            if finalLink.suffixIsPrivate { signals.append(Signal(.sharedHostingDomain, ["suffix": finalLink.publicSuffix ?? ""])) }
            switch ageDays {
            case nil: signals.append(Signal(.ageUnknown))
            case let d? where d < 30: signals.append(Signal(.domainVeryNew, ["days": String(d)]))
            case let d? where d < 183: signals.append(Signal(.domainNew, ["days": String(d)]))
            default: break
            }
        }

        var seen = Set<SignalID>()
        let ordered = signals.inStableOrder().filter { seen.insert($0.id).inserted }
        let level = ([original.level, analyzer.level(for: ordered)] + hopAnalyses.map(\.level)).max() ?? .normal
        return PeekReport(finalLink: finalLink, hopAnalyses: hopAnalyses, title: title, signals: ordered, level: level)
    }

    private static func lastHopArgs(_ capture: PeekCapture, psl: PublicSuffixList) -> [String: String] {
        guard let url = capture.hops.last?.url, let a = WebAddress.parse(url, psl: psl) else { return [:] }
        return ["host": a.link.hostUnicode]
    }

    private static func sniff(_ capture: PeekCapture) -> HTMLSniff? {
        guard !capture.body.isEmpty else { return nil }
        let ct = capture.contentType?.lowercased() ?? ""
        let firstByte = capture.body.first(where: { !($0 == 0x20 || $0 == 0x09 || $0 == 0x0A || $0 == 0x0D) })
        guard ct.contains("html") || firstByte == UInt8(ascii: "<") else { return nil }
        return HTMLSniffer.sniff(body: capture.body, contentType: capture.contentType)
    }

    private static func downloads(_ capture: PeekCapture, _ final: LinkInfo) -> Bool {
        if let d = capture.contentDisposition?.lowercased(), d.trimmingCharacters(in: .whitespaces).hasPrefix("attachment") { return true }
        let path = final.pathAndQuery.prefix { $0 != "?" && $0 != "#" }
        guard let name = path.split(separator: "/").last, let dot = name.lastIndex(of: ".") else { return false }
        return downloadExtensions.contains(name[name.index(after: dot)...].lowercased())
    }
}
