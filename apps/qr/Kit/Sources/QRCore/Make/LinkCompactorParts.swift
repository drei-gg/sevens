import Foundation

/// An http(s) link cut into the pieces `LinkCompactor` rewrites, each kept exactly as written.
/// The shared `WebAddress` parser decides what a link is and what its host is; the cutting
/// follows the same rules (last `@` ends userinfo, `[...]` is an IPv6 host, the last `:` after it
/// starts a port). Never uses Foundation.URL.
struct LinkCompactorParts {
    /// As written, e.g. `https` or `HTTPS`.
    var scheme: String
    /// `user:pw@` including the `@`, or empty.
    var userinfo: String
    /// The host as written.
    var host: String
    /// The parser's ASCII host (`xn--` form; IPv6 in brackets), lowercase as the parser gives it.
    var hostASCII: String
    /// `:8080` including the colon, or empty.
    var port: String
    /// Everything from the first `/`, `\`, `?` or `#` after the authority; may be empty.
    var rest: String

    private static let psl = PublicSuffixList(text: "")

    /// nil unless `link` is an absolute http(s) link written with exactly `//` after the scheme.
    init?(_ link: String) {
        let t = URLText.clean(link)
        guard let (name, after) = WebAddress.splitScheme(t), name == "http" || name == "https",
              after.hasPrefix("//"),
              let address = WebAddress.parse(t, psl: Self.psl) else { return nil }
        let body = after.dropFirst(2)
        if let first = body.first, first == "/" || first == "\\" { return nil }
        let end = body.firstIndex { "/\\?#".contains($0) } ?? body.endIndex
        let authority = body[..<end]
        var hostPort = authority
        var user = ""
        if let at = authority.lastIndex(of: "@") {
            user = String(authority[...at])
            hostPort = authority[authority.index(after: at)...]
        }
        var hostText = hostPort
        var portText = Substring()
        if hostPort.hasPrefix("[") {
            guard let close = hostPort.firstIndex(of: "]") else { return nil }
            hostText = hostPort[...close]
            portText = hostPort[hostPort.index(after: close)...]
        } else if let colon = hostPort.lastIndex(of: ":") {
            hostText = hostPort[..<colon]
            portText = hostPort[colon...]
        }
        scheme = String(t.prefix { $0 != ":" })
        userinfo = user
        host = String(hostText)
        hostASCII = address.link.hostASCII
        port = String(portText)
        rest = String(body[end...])
    }

    /// The port number when it is exactly the default for the scheme (`:443` on https, `:80` on
    /// http, leading zeros included).
    var hasDefaultPort: Bool {
        guard port.hasPrefix(":"), port.count > 1, port.count <= 6,
              port.dropFirst().allSatisfy({ $0.isASCII && $0.isNumber }), let n = Int(port.dropFirst()) else { return false }
        return n == (scheme.lowercased() == "https" ? 443 : 80)
    }

    func joined(scheme: String, host: String, port: String, rest: String) -> String {
        scheme + "://" + userinfo + host + port + rest
    }
}
