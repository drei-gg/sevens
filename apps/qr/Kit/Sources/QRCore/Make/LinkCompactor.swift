// Seam added at qr-contract-2. R4 owns this folder: keep every public signature; replace each
// `fatalError("R4")`.

/// SPEC §8, smaller codes and tracking tags. Pure text in, text out; used by Make only, never by
/// the checks. Only `http` and `https` links are touched; anything else comes back as nil
/// (optional results) or unchanged.
public enum LinkCompactor {
    /// M3: drops `:443` on https and `:80` on http, and a lone trailing `/` when there is no path,
    /// query or fragment after it. Nothing else (never `www.`, never http → https).
    public static func trimmed(_ link: String) -> String {
        fatalError("R4")
    }

    /// M1: the scheme and the host (ASCII `xn--` form; IPv6 hex too) in capitals. Userinfo, port,
    /// path, query and fragment are kept byte for byte. nil when the text isn't an http(s) link.
    public static func capitalized(_ link: String) -> String? {
        fatalError("R4")
    }

    /// `trimmed` then `capitalized`. The result parses (`WebAddress`) to the same
    /// `LinkInfo.normalized` as the input. nil when the text isn't an http(s) link.
    public static func compacted(_ link: String) -> String? {
        fatalError("R4")
    }

    /// M4: the query parameters (names as written) that match `list`, in order of appearance.
    public static func trackingParameters(in link: String, list: [TrackingParam]) -> [String] {
        fatalError("R4")
    }

    /// M4: the link without those parameters (and without a `?` left empty), or nil when it
    /// carries none.
    public static func removingTracking(from link: String, list: [TrackingParam]) -> String? {
        fatalError("R4")
    }
}
