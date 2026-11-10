// SPEC §8, smaller codes and tracking tags.

/// Pure text in, text out; used by Make only, never by the checks. Only `http` and `https` links
/// are touched; anything else comes back as nil (optional results) or unchanged.
public enum LinkCompactor {
    /// M3: drops `:443` on https and `:80` on http, and a lone trailing `/` when there is no path,
    /// query or fragment after it. Nothing else (never `www.`, never http → https).
    public static func trimmed(_ link: String) -> String {
        guard let p = LinkCompactorParts(link) else { return link }
        return p.joined(scheme: p.scheme, host: p.host, port: p.hasDefaultPort ? "" : p.port,
                        rest: p.rest == "/" ? "" : p.rest)
    }

    /// M1: the scheme and the host (ASCII `xn--` form; IPv6 hex too) in capitals. Userinfo, port,
    /// path, query and fragment are kept byte for byte. nil when the text isn't an http(s) link.
    public static func capitalized(_ link: String) -> String? {
        guard let p = LinkCompactorParts(link) else { return nil }
        return p.joined(scheme: p.scheme.uppercased(), host: p.hostASCII.uppercased(), port: p.port, rest: p.rest)
    }

    /// `trimmed` then `capitalized`. The result parses (`WebAddress`) to the same
    /// `LinkInfo.normalized` as the input. nil when the text isn't an http(s) link.
    public static func compacted(_ link: String) -> String? {
        capitalized(trimmed(link))
    }

    /// M4: the query parameters (names as written) that match `list`, in order of appearance.
    public static func trackingParameters(in link: String, list: [TrackingParam]) -> [String] {
        guard let q = query(of: link) else { return [] }
        return q.params.compactMap { name(of: $0).flatMap { n in list.contains { $0.matches(n) } ? n : nil } }
    }

    /// M4: the link without those parameters (and without a `?` left empty), or nil when it
    /// carries none.
    public static func removingTracking(from link: String, list: [TrackingParam]) -> String? {
        guard let q = query(of: link) else { return nil }
        let kept = q.params.filter { param in
            guard let n = name(of: param) else { return true }
            return !list.contains { $0.matches(n) }
        }
        guard kept.count < q.params.count else { return nil }
        let newQuery = kept.isEmpty ? "" : "?" + kept.joined(separator: "&")
        return q.before + newQuery + q.fragment
    }

    // MARK: Query splitting

    private struct Query {
        var before: String
        var params: [String]
        var fragment: String
    }

    /// The parameter text of a link that has a non-empty query; kept pieces are verbatim.
    private static func query(of link: String) -> Query? {
        guard let p = LinkCompactorParts(link) else { return nil }
        let rest = p.rest
        let hash = rest.firstIndex(of: "#") ?? rest.endIndex
        let beforeFragment = rest[..<hash]
        guard let mark = beforeFragment.firstIndex(of: "?") else { return nil }
        let queryText = beforeFragment[beforeFragment.index(after: mark)...]
        guard !queryText.isEmpty else { return nil }
        let prefix = p.joined(scheme: p.scheme, host: p.host, port: p.port, rest: String(rest[..<mark]))
        return Query(before: prefix,
                     params: queryText.split(separator: "&", omittingEmptySubsequences: false).map(String.init),
                     fragment: String(rest[hash...]))
    }

    /// The name part of `name=value` (or the whole piece); nil when empty.
    private static func name(of param: String) -> String? {
        let n = param.prefix { $0 != "=" }
        return n.isEmpty ? nil : String(n)
    }
}
