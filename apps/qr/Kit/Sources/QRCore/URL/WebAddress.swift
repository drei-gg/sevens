import Foundation

// WP 1.1 owns this file. Signatures below are the seam other packages call; keep them.

/// A browser-like address parser (D6). Never uses Foundation.URL to find the host.
public struct WebAddress: Sendable, Hashable {
    public enum HostKind: Sendable, Hashable { case domain, ipv4, ipv6 }

    public var link: LinkInfo
    public var hostKind: HostKind
    /// A label failed punycode conversion: the checks add `mixedScript`.
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

    /// The scheme per the WHATWG URL rules (letters, digits, `+ - .` before the first `:`,
    /// starting with a letter), lowercased; nil when the text has none. Input is trimmed first.
    public static func scheme(of text: String) -> String? {
        splitScheme(URLText.clean(text))?.scheme
    }

    /// An absolute http/https address. nil for other schemes or when there is no host.
    public static func parse(_ text: String, psl: PublicSuffixList) -> WebAddress? {
        build(cleaned: URLText.clean(text), original: text, psl: psl, assumedHTTPS: false)
    }

    /// D9: text with no scheme and no spaces whose host ends in a suffix with an explicit PSL
    /// rule (`www.example.com`, `example.com/x`) → https assumed. nil otherwise.
    public static func parseSchemeless(_ text: String, psl: PublicSuffixList) -> WebAddress? {
        let t = URLText.clean(text)
        let scalars = t.unicodeScalars
        guard !t.isEmpty, !scalars.contains(where: { $0.properties.isWhitespace || $0.value < 0x20 || $0.value == 0x7F }),
              !t.contains("@"), !t.hasPrefix("/"), !t.hasPrefix("."), scheme(of: t) == nil else { return nil }
        let hostPart = t.prefix { !"/?#".contains($0) }
        let labels = hostPart.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 2, labels.allSatisfy({ !$0.isEmpty && $0.unicodeScalars.allSatisfy(URLText.isLabelScalar) }) else { return nil }
        guard var address = build(cleaned: "https://" + t, original: text, psl: psl, assumedHTTPS: true),
              address.hostKind == .domain, address.suffixIsKnown, address.link.registrableDomain != nil else { return nil }
        address.assumedHTTPS = true
        return address
    }

    /// Resolves a reference (absolute, scheme-relative, absolute-path or relative) against a
    /// base, as a browser would for a redirect `Location` or a form `action`. nil if the result
    /// isn't http/https.
    public static func resolve(_ reference: String, against base: LinkInfo, psl: PublicSuffixList) -> WebAddress? {
        let ref = URLText.clean(reference)
        guard base.scheme == "http" || base.scheme == "https" else { return nil }
        if let (scheme, _) = splitScheme(ref) {
            guard scheme == "http" || scheme == "https" else { return nil }
            return build(cleaned: ref, original: ref, psl: psl, assumedHTTPS: false)
        }
        let isSlash: (Character) -> Bool = { $0 == "/" || $0 == "\\" }
        let first2 = Array(ref.prefix(2))
        if first2.count == 2, isSlash(first2[0]), isSlash(first2[1]) {
            return build(cleaned: base.scheme + ":" + ref, original: ref, psl: psl, assumedHTTPS: false)
        }
        var authority = base.scheme + "://"
        if let u = base.userinfo { authority += u + "@" }
        authority += base.hostASCII
        if let p = base.port { authority += ":\(p)" }

        let (basePath, baseRest, _) = URLText.splitPath(base.pathAndQuery)
        let baseQuery = String(baseRest.prefix { $0 != "#" })
        let (refPath, refRest, _) = URLText.splitPath(ref, backslashes: true)
        var path: String
        var rest: String
        if ref.isEmpty {
            path = basePath; rest = baseQuery
        } else if ref.hasPrefix("#") {
            path = basePath; rest = baseQuery + ref
        } else if ref.hasPrefix("?") {
            path = basePath; rest = ref
        } else if refPath.hasPrefix("/") {
            path = refPath; rest = refRest
        } else {
            let dir = basePath.range(of: "/", options: .backwards).map { String(basePath[..<$0.upperBound]) } ?? "/"
            path = dir + refPath; rest = refRest
        }
        path = URLText.removeDotSegments(path)
        let absolute = authority + path + rest
        return build(cleaned: absolute, original: absolute, psl: psl, assumedHTTPS: false)
    }

    // MARK: - Internals

    /// Scheme and the text after the colon, or nil.
    static func splitScheme(_ cleaned: String) -> (scheme: String, rest: String)? {
        guard let colon = cleaned.firstIndex(of: ":") else { return nil }
        let name = cleaned[..<colon]
        guard let f = name.unicodeScalars.first, f.isASCII, f.properties.isAlphabetic,
              name.unicodeScalars.allSatisfy({ $0.isASCII && ($0.properties.isAlphabetic || ("0"..."9").contains($0) || $0 == "+" || $0 == "-" || $0 == ".") })
        else { return nil }
        return (name.lowercased(), String(cleaned[cleaned.index(after: colon)...]))
    }

    static func build(cleaned: String, original: String, psl: PublicSuffixList, assumedHTTPS: Bool) -> WebAddress? {
        guard let (scheme, afterColon) = splitScheme(cleaned), scheme == "http" || scheme == "https" else { return nil }
        let body = afterColon.drop { $0 == "/" || $0 == "\\" }
        let authorityEnd = body.firstIndex { "/\\?#".contains($0) } ?? body.endIndex
        let authority = body[..<authorityEnd]
        var remainder = String(body[authorityEnd...])

        var userinfo: String?
        var hostPort = Substring(authority)
        if let at = authority.lastIndex(of: "@") {
            let u = String(authority[..<at])
            userinfo = u.isEmpty ? nil : u
            hostPort = authority[authority.index(after: at)...]
        }
        var rawHost = hostPort
        var port: Int?
        if hostPort.hasPrefix("[") {
            guard let close = hostPort.firstIndex(of: "]") else { return nil }
            rawHost = hostPort[...close]
            let after = hostPort[hostPort.index(after: close)...]
            if !after.isEmpty {
                guard after.hasPrefix(":") else { return nil }
                let p = parsePort(after.dropFirst())
                guard p.ok else { return nil }
                port = p.value
            }
        } else if let colon = hostPort.lastIndex(of: ":") {
            rawHost = hostPort[..<colon]
            let p = parsePort(hostPort[hostPort.index(after: colon)...])
            guard !rawHost.contains(":"), p.ok else { return nil }
            port = p.value
        }
        guard let host = URLHost.parse(String(rawHost)) else { return nil }
        if port == (scheme == "https" ? 443 : 80) { port = nil }

        // Path part: `\` is `/` for http/https; fragment and query are kept as written.
        if remainder.isEmpty || remainder.hasPrefix("?") || remainder.hasPrefix("#") { remainder = "/" + remainder }
        let (p, rest, _) = URLText.splitPath(remainder, backslashes: true)
        let pathAndQuery = p + rest

        var registrable: String?, suffix: String?
        var isPrivate = false, known = false
        if host.kind == .domain {
            let m = psl.match(hostASCII: host.ascii)
            // Contract (LinkInfo): nil for unknown suffixes, i.e. when only PSL's default rule applied.
            registrable = m.matchedExplicitRule ? m.registrableDomain.map(URLHost.unicodeForm) : nil
            suffix = URLHost.unicodeForm(m.publicSuffix)
            isPrivate = m.isPrivate
            known = m.matchedExplicitRule
        }
        var normalized = scheme + "://"
        if let userinfo { normalized += userinfo + "@" }
        normalized += host.ascii
        if let port { normalized += ":\(port)" }
        normalized += pathAndQuery
        let link = LinkInfo(original: original, scheme: scheme, userinfo: userinfo, hostASCII: host.ascii,
                            hostUnicode: host.unicode, registrableDomain: registrable, publicSuffix: suffix,
                            suffixIsPrivate: isPrivate, port: port, pathAndQuery: pathAndQuery,
                            isIPAddress: host.kind != .domain, normalized: normalized)
        return WebAddress(link: link, hostKind: host.kind, hasMalformedLabel: host.malformed,
                          suffixIsKnown: known, assumedHTTPS: assumedHTTPS)
    }

    private static func parsePort(_ s: Substring) -> (ok: Bool, value: Int?) {
        if s.isEmpty { return (true, nil) }
        guard s.count <= 5, s.allSatisfy({ $0.isASCII && $0.isNumber }), let v = Int(s), v <= 65535 else { return (false, nil) }
        return (true, v)
    }
}
