import Foundation

// WP 1.6 owns this file. Signatures below are the seam other packages call; keep them.

/// IANA's RDAP bootstrap file for DNS (data.iana.org/rdap/dns.json).
public struct RDAPBootstrap: Sendable {
    let raw: Data

    /// An empty or unreadable file gives a bootstrap that knows no services.
    public init(json: Data) {
        raw = json
    }

    /// The base URL (ending in "/") for a top-level label (ASCII, lowercase), or nil.
    public func baseURL(forTLD tld: String) -> String? { fatalError("WP 1.6") }

    /// `<base>domain/<name>` for an ASCII registrable domain, or nil when its ending has no service.
    public func queryURL(forDomain asciiDomain: String) -> String? { fatalError("WP 1.6") }
}
