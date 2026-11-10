import SwiftUI
import QRCore
import SevenKit

/// What to show for a link, decided without a view so it can be tested.
enum ResultLinkDisplay {
    /// The registrable domain: the largest text on a link result (§6.13). IPs and unknown
    /// suffixes show the host; dangerous schemes show the scheme.
    static func headline(_ link: LinkInfo) -> String {
        if let domain = link.registrableDomain, !domain.isEmpty { return domain }
        if !link.hostUnicode.isEmpty { return link.hostUnicode }
        return link.scheme.isEmpty ? link.original : link.scheme + ":"
    }

    /// The full host when it is more than the registrable domain (`login.example.com`).
    static func fullHost(_ link: LinkInfo) -> String? {
        guard !link.hostUnicode.isEmpty, link.hostUnicode != headline(link) else { return nil }
        return link.hostUnicode
    }

    /// The `xn--` form, only when the displayed host differs from it.
    static func punycode(_ link: LinkInfo) -> String? {
        guard !link.hostASCII.isEmpty, link.hostASCII != link.hostUnicode.lowercased() else { return nil }
        return link.hostASCII
    }

    /// What Open uses, when it isn't what was scanned (D9: a schemeless link is opened as https).
    static func openedAs(_ link: LinkInfo) -> String? {
        guard ResultActionPlan.canOpen(link), !link.normalized.isEmpty, link.normalized != link.original else { return nil }
        return link.normalized
    }
}

struct ResultLinkView: View {
    let link: LinkInfo

    var body: some View {
        ResultCard {
            Text(ResultLinkDisplay.headline(link))
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(Tokens.ink)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let host = ResultLinkDisplay.fullHost(link) {
                ResultField(label: String(localized: "result.link.host", defaultValue: "Full address of the site", bundle: .module),
                            value: host)
            }
            if let punycode = ResultLinkDisplay.punycode(link) {
                ResultField(label: String(localized: "result.link.punycode", defaultValue: "Written in plain letters as", bundle: .module),
                            value: punycode, monospaced: true, muted: true)
            }
            if let userinfo = link.userinfo, !userinfo.isEmpty {
                ResultField(label: String(localized: "result.link.userinfo", defaultValue: "Text before the @ sign", bundle: .module),
                            value: userinfo, muted: true)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "result.link.full", defaultValue: "The whole link", bundle: .module))
                    .font(.caption).foregroundStyle(Tokens.muted)
                Text(link.original)
                    .font(.footnote)
                    .foregroundStyle(Tokens.muted)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let opened = ResultLinkDisplay.openedAs(link) {
                ResultField(label: String(localized: "result.link.openedAs", defaultValue: "Opens as", bundle: .module),
                            value: opened, muted: true)
            }
        }
    }
}
