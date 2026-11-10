import Foundation
import QRCore

/// The URLs the actions hand to `openURL` (SPEC §3). All built with `URLComponents`, so scanned
/// text is percent-encoded and never concatenated into a URL string.
enum ResultActionURLs {
    /// What Open uses: the normalized link, for http(s) only.
    static func open(_ link: LinkInfo) -> URL? {
        guard ResultActionPlan.canOpen(link), let url = URL(string: link.normalized),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        return url
    }

    /// Digits, `+`, `*`, `#` and pause characters only.
    static func dialable(_ number: String) -> String {
        String(number.unicodeScalars.filter { "0123456789+*#,;".unicodeScalars.contains($0) })
    }

    static func call(_ number: String) -> URL? {
        let digits = dialable(number)
        guard digits.contains(where: \.isNumber) else { return nil }
        var parts = URLComponents()
        parts.scheme = "tel"
        parts.path = digits
        return parts.url
    }

    static func mail(_ info: EmailInfo) -> URL? {
        let address = info.address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !address.isEmpty else { return nil }
        var parts = URLComponents()
        parts.scheme = "mailto"
        parts.path = address
        var items: [URLQueryItem] = []
        if let subject = info.subject, !subject.isEmpty { items.append(URLQueryItem(name: "subject", value: subject)) }
        if let body = info.body, !body.isEmpty { items.append(URLQueryItem(name: "body", value: body)) }
        if !items.isEmpty { parts.queryItems = items }
        return parts.url
    }

    /// `sms:` fallback when the composer isn't available.
    static func sms(_ info: SMSInfo) -> URL? {
        let digits = dialable(info.number)
        guard !digits.isEmpty else { return nil }
        var parts = URLComponents()
        parts.scheme = "sms"
        parts.path = digits
        if let body = info.body, !body.isEmpty { parts.queryItems = [URLQueryItem(name: "body", value: body)] }
        return parts.url
    }

    private static func isCoordinate(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespaces)
        return !t.isEmpty && t.unicodeScalars.allSatisfy { "0123456789+-.".unicodeScalars.contains($0) } && Double(t) != nil
    }

    /// `maps:?ll=lat,lon` plus `q` when the code has one.
    static func maps(_ info: GeoInfo) -> URL? {
        guard isCoordinate(info.latitude), isCoordinate(info.longitude) else { return nil }
        let lat = info.latitude.trimmingCharacters(in: .whitespaces)
        let lon = info.longitude.trimmingCharacters(in: .whitespaces)
        var parts = URLComponents()
        parts.scheme = "maps"
        var items = [URLQueryItem(name: "ll", value: "\(lat),\(lon)")]
        if let q = info.query, !q.isEmpty { items.append(URLQueryItem(name: "q", value: q)) }
        parts.queryItems = items
        return parts.url
    }

    /// "Open in…": the original payload, only when it is URI-shaped (UPI, crypto).
    static func openPayment(_ analysis: Analysis) -> URL? {
        guard ResultActionPlan.isURIShapedPayment(analysis.payload), let text = analysis.code.text,
              let url = URL(string: text), let scheme = url.scheme, !scheme.isEmpty else { return nil }
        return url
    }
}
