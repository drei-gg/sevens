import Foundation

/// SPEC §3: decides which parser handles a scan. First match wins; a parser that returns nil
/// falls through to the next rule. Also runs the checks that belong to the payload itself.
struct Classifier: Sendable {
    let ctx: CheckContext
    var data: BundledData { ctx.data }

    struct Result: Sendable {
        var payload: Payload
        var signals: [Signal]
    }

    static let maxBytes = 8192

    func classify(_ code: ScannedCode) -> Result {
        guard let raw = code.text else { return Result(payload: .binary(byteCount: code.bytes.count), signals: []) }
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return Result(payload: .binary(byteCount: code.bytes.count), signals: []) }
        if raw.utf8.count > Self.maxBytes {
            let cut = Self.truncated(raw)
            return Result(payload: .text(TextInfo(text: cut, isTruncated: true, links: EmbeddedLinks.find(in: cut))), signals: [])
        }
        let head = String(t.prefix(32)).lowercased()
        let psl = data.publicSuffixes

        if head.hasPrefix("wifi:"), let w = WiFiParser.parse(t) { return wifi(w) }
        if head.hasPrefix("begin:vcard") || head.hasPrefix("mecard:"), var c = ContactParser.parse(t) {
            c.links = EmbeddedLinks.merge(c.urls.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty },
                                          EmbeddedLinks.find(in: c.note))
            return Result(payload: .contact(c), signals: [])
        }
        if head.hasPrefix("begin:vevent") || (head.hasPrefix("begin:vcalendar") && t.range(of: "BEGIN:VEVENT", options: .caseInsensitive) != nil),
           var e = EventParser.parse(t) {
            e.links = EmbeddedLinks.merge((e.url.map { [$0] } ?? []).filter { !$0.isEmpty },
                                          EmbeddedLinks.find(in: e.description), EmbeddedLinks.find(in: e.location))
            return Result(payload: .event(e), signals: [])
        }
        if head.hasPrefix("000201"), t.count >= 8, t.dropFirst(t.count - 8).hasPrefix("6304"),
           let p = EMVParser.parse(t, networks: data.paymentNetworks.emv, currencies: data.currencies) { return payment(p) }
        var firstLineScalars = String.UnicodeScalarView()
        firstLineScalars.append(contentsOf: t.unicodeScalars.prefix { $0 != "\n" && $0 != "\r" })
        let firstLine = String(firstLineScalars).trimmingCharacters(in: .whitespaces)
        if firstLine == "BCD", let p = EPCParser.parse(t) { return payment(p) }
        if firstLine == "SPC", let p = SwissQRParser.parse(t) { return payment(p) }
        if head.hasPrefix("upi://"), let p = UPIParser.parse(t) { return payment(p) }
        if data.paymentNetworks.cryptoSchemes.contains(where: { head.hasPrefix($0.scheme.lowercased() + ":") }),
           let c = CryptoURIParser.parse(t, schemes: data.paymentNetworks.cryptoSchemes) {
            return Result(payload: .crypto(c), signals: [])
        }
        if head.hasPrefix("tel:"), let p = PhoneParser.parse(t) { return Result(payload: .phone(p), signals: []) }
        if head.hasPrefix("sms:") || head.hasPrefix("smsto:"), var s = SMSParser.parse(t) {
            s.links = EmbeddedLinks.find(in: s.body)
            return Result(payload: .sms(s), signals: [])
        }
        if head.hasPrefix("mailto:") || head.hasPrefix("matmsg:"), var m = EmailParser.parse(t) {
            m.links = EmbeddedLinks.find(in: m.body)
            return Result(payload: .email(m), signals: [])
        }
        if head.hasPrefix("geo:"), let g = GeoParser.parse(t) { return Result(payload: .geo(g), signals: []) }

        let scheme = WebAddress.scheme(of: raw)
        if scheme == "http" || scheme == "https", let a = WebAddress.parse(raw, psl: psl) {
            return Result(payload: .link(a.link), signals: LinkChecks.signals(for: a, ctx))
        }
        if let scheme, scheme != "http", scheme != "https" {
            let cleaned = URLText.clean(raw)
            let rest = WebAddress.splitScheme(cleaned)?.rest ?? ""
            if let danger = DangerousSchemeCheck.signal(scheme: scheme) {
                let link = LinkInfo(original: raw, scheme: scheme, hostASCII: "", hostUnicode: "", pathAndQuery: rest,
                                    normalized: scheme + ":" + rest)
                return Result(payload: .link(link), signals: [danger])
            }
            // Text such as "Total: 5", "C:\\temp" or a vCard/iCal block that didn't parse isn't an
            // app link: it needs a real scheme name and something right after the colon.
            let afterColon = t.unicodeScalars.drop { $0 != ":" }.dropFirst().first
            if scheme.count >= 2, scheme != "begin", !rest.isEmpty, let c = afterColon, !c.properties.isWhitespace {
                return Result(payload: .appLink(AppLinkInfo(scheme: scheme, original: raw)),
                              signals: [UnknownSchemeCheck.signal(scheme: scheme)])
            }
        }
        if let c = CryptoAddress.detect(t) { return Result(payload: .crypto(c), signals: []) }
        if let a = WebAddress.parseSchemeless(raw, psl: psl) {
            return Result(payload: .link(a.link), signals: LinkChecks.signals(for: a, ctx))
        }
        return Result(payload: .text(TextInfo(text: raw, isTruncated: false, links: EmbeddedLinks.find(in: raw))), signals: [])
    }

    private func wifi(_ w: WiFiInfo) -> Result {
        Result(payload: .wifi(w), signals: [OpenWiFiCheck.signal(w)].compactMap { $0 })
    }

    private func payment(_ p: PaymentInfo) -> Result {
        Result(payload: .payment(p), signals: [PaymentChecksumCheck.signal(p)].compactMap { $0 })
    }

    /// The first 8 KB of UTF-8, cut at a scalar boundary.
    static func truncated(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        var bytes = 0
        for sc in s.unicodeScalars {
            bytes += String(sc).utf8.count
            if bytes > maxBytes { break }
            out.append(sc)
        }
        return String(out)
    }
}
