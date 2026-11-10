// WP 1.1 owns this file. Signatures below are the seam other packages call; keep them.

/// A browser-like address parser (D6, PLAN §6.1). Never uses Foundation.URL to find the host.
public struct WebAddress: Sendable, Hashable {
    public enum HostKind: Sendable, Hashable { case domain, ipv4, ipv6 }

    public var link: LinkInfo
    public var hostKind: HostKind
    /// A label failed punycode conversion (PLAN §6.1 step 5): the checks add `mixedScript`.
    public var hasMalformedLabel: Bool
    /// The public suffix came from an explicit PSL rule.
    public var suffixIsKnown: Bool
    /// D9: no scheme was given and `https` was assumed (the checks add `assumedHTTPS`).
    public var assumedHTTPS: Bool

    public init(link: LinkInfo, hostKind: HostKind, hasMalformedLabel: Bool, suffixIsKnown: Bool, assumedHTTPS: Bool) {
        self.link = link
        self.hostKind = hostKind
        self.hasMalformedLabel = hasMalformedLabel
        self.suffixIsKnown = suffixIsKnown
        self.assumedHTTPS = assumedHTTPS
    }

    /// The scheme per PLAN §6.1 step 2 (letters, digits, `+ - .` before the first `:`,
    /// starting with a letter), lowercased; nil when the text has none. Input is trimmed first.
    public static func scheme(of text: String) -> String? {
        fatalError("WP 1.1")
    }

    /// An absolute http/https address. nil for other schemes or when there is no host.
    public static func parse(_ text: String, psl: PublicSuffixList) -> WebAddress? {
        fatalError("WP 1.1")
    }

    /// D9: text with no scheme and no spaces whose host ends in a suffix with an explicit PSL
    /// rule (`www.example.com`, `example.com/x`) → https assumed. nil otherwise.
    public static func parseSchemeless(_ text: String, psl: PublicSuffixList) -> WebAddress? {
        fatalError("WP 1.1")
    }

    /// Resolves a reference (absolute, scheme-relative, absolute-path or relative) against a
    /// base, as a browser would for a redirect `Location` or a form `action`. nil if the result
    /// isn't http/https.
    public static func resolve(_ reference: String, against base: LinkInfo, psl: PublicSuffixList) -> WebAddress? {
        fatalError("WP 1.1")
    }
}
