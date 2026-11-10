import Foundation

/// IANA's RDAP bootstrap file for DNS (data.iana.org/rdap/dns.json).
public struct RDAPBootstrap: Sendable {
    /// Lowercase ASCII top-level label -> base URL ending in "/".
    private let services: [String: String]

    /// An empty or unreadable file gives a bootstrap that knows no services.
    public init(json: Data) {
        var map: [String: String] = [:]
        if json.count <= 4_000_000,
           let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
           let list = root["services"] as? [Any] {
            for entry in list {
                guard let pair = entry as? [Any], pair.count >= 2,
                      let tlds = pair[0] as? [Any], let urls = pair[1] as? [Any] else { continue }
                let strings = urls.compactMap { $0 as? String }.map { $0.trimmingCharacters(in: .whitespaces) }
                let pick = strings.first { $0.lowercased().hasPrefix("https://") }
                    ?? strings.first { !$0.isEmpty }
                guard var base = pick, !base.isEmpty else { continue }
                if !base.hasSuffix("/") { base += "/" }
                for case let tld as String in tlds {
                    let key = tld.lowercased()
                    if !key.isEmpty, map[key] == nil { map[key] = base }
                }
            }
        }
        services = map
    }

    /// The base URL (ending in "/") for a top-level label (ASCII, lowercase), or nil.
    public func baseURL(forTLD tld: String) -> String? {
        services[tld.lowercased()]
    }

    /// `<base>domain/<name>` for an ASCII registrable domain, or nil when its ending has no service.
    public func queryURL(forDomain asciiDomain: String) -> String? {
        let domain = asciiDomain.lowercased()
        guard !domain.isEmpty, domain.utf8.count <= 253,
              domain.utf8.allSatisfy({ ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2D || $0 == 0x2E })
        else { return nil }
        let labels = domain.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 1, let last = labels.last, !last.isEmpty,
              let base = services[String(last)] else { return nil }
        return base + "domain/" + domain
    }
}
