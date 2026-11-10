import Foundation

// Seam added at qr-contract-2. R5 owns this file: keep every public signature; replace each
// `fatalError("R5")`.

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
        fatalError("R5")
    }

    /// Adds (or moves to the front) one domain.
    public mutating func add(_ registrableDomain: String, at date: Date) {
        fatalError("R5")
    }

    public mutating func remove(_ registrableDomain: String) {
        fatalError("R5")
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
        fatalError("R5")
    }

    /// The analyzer's final step for one payload's own signals: when the payload is a link whose
    /// registrable domain is known, drop the `listBased` signals and add `knownByYou`. Every other
    /// signal stays. Anything else comes back unchanged.
    public func apply(to signals: [Signal], payload: Payload) -> [Signal] {
        fatalError("R5")
    }
}
