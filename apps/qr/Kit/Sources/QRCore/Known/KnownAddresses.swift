import Foundation

/// SPEC §3, §4.4: registrable domains the person told us they know. Pure value; QRUI stores it in
/// its own file beside History and passes it to `Analyzer(data:known:)`.
public struct KnownAddresses: Codable, Sendable, Hashable {
    public struct Entry: Codable, Sendable, Hashable {
        /// A registrable domain, Unicode form, lowercase (as in `LinkInfo.registrableDomain`).
        public var domain: String
        public var added: Date

        public init(domain: String, added: Date) {
            self.domain = domain
            self.added = added
        }
    }

    /// Newest first.
    public private(set) var entries: [Entry]

    public init(entries: [Entry] = []) {
        self.entries = entries
    }

    public var isEmpty: Bool { entries.isEmpty }

    public func contains(_ registrableDomain: String) -> Bool {
        let key = Self.normalized(registrableDomain)
        return !key.isEmpty && entries.contains { $0.domain == key }
    }

    /// Lowercase, trimmed, no trailing dot: the form `LinkInfo.registrableDomain` has.
    static func normalized(_ domain: String) -> String {
        var text = domain.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        while text.hasSuffix(".") { text.removeLast() }
        return text
    }

    /// The most entries kept; the oldest fall off. A person won't reach it; a hostile import could.
    public static let maxEntries = 1_000

    /// Adds (or moves to the front) one domain.
    public mutating func add(_ registrableDomain: String, at date: Date) {
        let key = Self.normalized(registrableDomain)
        guard !key.isEmpty else { return }
        entries.removeAll { $0.domain == key }
        entries.insert(Entry(domain: key, added: date), at: 0)
        if entries.count > Self.maxEntries { entries.removeLast(entries.count - Self.maxEntries) }
    }

    public mutating func remove(_ registrableDomain: String) {
        let key = Self.normalized(registrableDomain)
        entries.removeAll { $0.domain == key }
    }

    public mutating func removeAll() {
        entries.removeAll()
    }

    /// The signals that come only from a bundled list. Only these can be overridden.
    public static let listBased: Set<SignalID> = [
        .brandOtherEnding, .brandInDomain, .nearBrand, .lookalikeBrand, .riskyTLD, .shortener,
    ]

    /// The registrable domain to offer "I know this address" for, or nil: only for a link whose
    /// non-info signals are all `listBased` (at least one), not already known.
    public func offer(for analysis: Analysis) -> String? {
        guard case .link(let link) = analysis.payload, !link.isIPAddress,
              let domain = link.registrableDomain.map(Self.normalized), !domain.isEmpty,
              !contains(domain) else { return nil }
        let own = analysis.signals.filter { $0.severity != .info }
        guard !own.isEmpty, own.allSatisfy({ Self.listBased.contains($0.id) }) else { return nil }
        return domain
    }

    /// The analyzer's final step for one payload's own signals: when the payload is a link whose
    /// registrable domain is known, drop the `listBased` signals and add `knownByYou`. Every other
    /// signal stays. Anything else comes back unchanged.
    public func apply(to signals: [Signal], payload: Payload) -> [Signal] {
        guard case .link(let link) = payload, !link.isIPAddress,
              let domain = link.registrableDomain, contains(domain) else { return signals }
        let kept = signals.filter { !Self.listBased.contains($0.id) && $0.id != .knownByYou }
        return kept + [Signal(.knownByYou, ["domain": Self.normalized(domain)])]
    }
}
