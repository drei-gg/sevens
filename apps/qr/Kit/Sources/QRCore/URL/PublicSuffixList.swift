// WP 1.1 owns this file. Signatures below are the seam other packages call; keep them.

/// Mozilla's Public Suffix List (ICANN and private sections, wildcard and exception rules).
public struct PublicSuffixList: Sendable {
    public struct Match: Sendable, Hashable {
        /// ASCII (xn--) form.
        public var publicSuffix: String
        /// ASCII form; nil when the host *is* a public suffix.
        public var registrableDomain: String?
        /// The matched rule is in the PRIVATE section (e.g. github.io).
        public var isPrivate: Bool
        /// false when only PSL's default rule ("*") applied (D9 uses this).
        public var matchedExplicitRule: Bool
        /// The registrable domain computed with the ICANN section only (for RDAP, PLAN §6.5).
        public var icannRegistrableDomain: String?

        public init(publicSuffix: String, registrableDomain: String?, isPrivate: Bool,
                    matchedExplicitRule: Bool, icannRegistrableDomain: String?) {
            self.publicSuffix = publicSuffix
            self.registrableDomain = registrableDomain
            self.isPrivate = isPrivate
            self.matchedExplicitRule = matchedExplicitRule
            self.icannRegistrableDomain = icannRegistrableDomain
        }
    }

    let source: String

    /// Parses the `.dat` text. An empty string gives a list where only the default rule applies.
    public init(text: String) {
        source = text
    }

    /// `hostASCII`: lowercased, xn-- form, no trailing dot.
    public func match(hostASCII: String) -> Match {
        fatalError("WP 1.1")
    }
}
