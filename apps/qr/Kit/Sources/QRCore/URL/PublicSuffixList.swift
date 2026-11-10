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

    /// Rule (ASCII, lowercase) → "is in the PRIVATE section". Immutable after init.
    private let rules: [String: Bool]
    /// Parent of a `*.parent` rule → private flag.
    private let wildcards: [String: Bool]
    /// Text after `!` → private flag.
    private let exceptions: [String: Bool]
    private let maxLabels: Int

    /// Parses the `.dat` text. An empty string gives a list where only the default rule applies.
    public init(text: String) {
        var rules: [String: Bool] = [:]
        var wildcards: [String: Bool] = [:]
        var exceptions: [String: Bool] = [:]
        var maxLabels = 1
        var isPrivate = false
        for rawLine in text.split(omittingEmptySubsequences: true, whereSeparator: { $0 == "\n" || $0 == "\r\n" || $0 == "\r" }) {
            if rawLine.hasPrefix("//") {
                if rawLine.contains("===BEGIN PRIVATE DOMAINS===") { isPrivate = true }
                else if rawLine.contains("===END PRIVATE DOMAINS===") { isPrivate = false }
                continue
            }
            guard let token = rawLine.split(whereSeparator: { $0 == " " || $0 == "\t" }).first else { continue }
            var rule = String(token).lowercased()
            let isException = rule.hasPrefix("!")
            if isException { rule.removeFirst() }
            let isWildcard = rule.hasPrefix("*.")
            if isWildcard { rule.removeFirst(2) }
            guard let ascii = Self.toASCII(rule) else { continue }
            func store(_ d: inout [String: Bool]) {
                // A rule listed in both sections counts as ICANN.
                d[ascii] = (d[ascii] ?? isPrivate) && isPrivate
            }
            if isException { store(&exceptions) }
            else if isWildcard { store(&wildcards) }
            else { store(&rules) }
            maxLabels = max(maxLabels, ascii.split(separator: ".").count + (isWildcard ? 1 : 0))
        }
        self.rules = rules
        self.wildcards = wildcards
        self.exceptions = exceptions
        self.maxLabels = maxLabels
    }

    private static func toASCII(_ rule: String) -> String? {
        var out: [String] = []
        for label in rule.split(separator: ".", omittingEmptySubsequences: false) {
            if label.isEmpty { return nil }
            if label.unicodeScalars.allSatisfy({ $0.isASCII }) { out.append(String(label)) }
            else if let e = Punycode.encode(String(label)) { out.append("xn--" + e) }
            else { return nil }
        }
        return out.joined(separator: ".")
    }

    private struct Rule { var length: Int; var explicit: Bool; var isPrivate: Bool }

    private func prevailing(_ labels: [Substring], icannOnly: Bool) -> Rule {
        let n = labels.count
        let limit = min(n, maxLabels)
        func key(_ k: Int) -> String { labels[(n - k)...].joined(separator: ".") }
        func usable(_ flag: Bool?) -> Bool? {
            guard let flag else { return nil }
            return (icannOnly && flag) ? nil : flag
        }
        for k in stride(from: limit, through: 1, by: -1) {
            if let flag = usable(exceptions[key(k)]) {
                return Rule(length: k - 1, explicit: true, isPrivate: flag)
            }
        }
        var best = Rule(length: 1, explicit: false, isPrivate: false)
        for k in 1...limit {
            if let flag = usable(rules[key(k)]) {
                best = Rule(length: k, explicit: true, isPrivate: flag)
            }
            if k >= 2, k <= n, let flag = usable(wildcards[key(k - 1)]) {
                best = Rule(length: k, explicit: true, isPrivate: flag)
            }
        }
        return best
    }

    /// `hostASCII`: lowercased, xn-- form, no trailing dot.
    public func match(hostASCII: String) -> Match {
        let labels = hostASCII.split(separator: ".", omittingEmptySubsequences: false)
        if hostASCII.isEmpty || labels.contains(where: { $0.isEmpty }) {
            return Match(publicSuffix: hostASCII, registrableDomain: nil, isPrivate: false,
                         matchedExplicitRule: false, icannRegistrableDomain: nil)
        }
        func build(_ r: Rule) -> (suffix: String, domain: String?) {
            let n = labels.count
            let len = min(r.length, n)
            let suffix = labels[(n - len)...].joined(separator: ".")
            let domain = n > len ? labels[(n - len - 1)...].joined(separator: ".") : nil
            return (suffix, domain)
        }
        let all = prevailing(labels, icannOnly: false)
        let icann = prevailing(labels, icannOnly: true)
        let a = build(all)
        return Match(publicSuffix: a.suffix, registrableDomain: a.domain, isPrivate: all.isPrivate,
                     matchedExplicitRule: all.explicit, icannRegistrableDomain: build(icann).domain)
    }
}
