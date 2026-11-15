public enum CryptoURIParser {
    /// `bitcoin:`, `ethereum:`, `lightning:` and every scheme in `schemes`, or nil.
    public static func parse(_ text: String, schemes: [CryptoScheme]) -> CryptoInfo? {
        guard PaymentText.withinCap(text) else { return nil }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let colon = t.firstIndex(of: ":") else { return nil }
        let scheme = t[t.startIndex..<colon].lowercased()
        var rest = t[t.index(after: colon)...]
        if rest.hasPrefix("//") { rest = rest.dropFirst(2) }
        if let hash = rest.firstIndex(of: "#") { rest = rest[rest.startIndex..<hash] }
        var path = rest
        var params: [String: String] = [:]
        if let q = rest.firstIndex(of: "?") {
            path = rest[rest.startIndex..<q]
            params = PaymentText.queryItems(rest[rest.index(after: q)...])
        }
        let address = path.trimmingCharacters(in: .whitespaces)

        switch scheme {
        case "lightning":
            guard !address.isEmpty else { return nil }
            return lightning(scheme: scheme, invoice: address)
        case "bitcoin":
            if address.isEmpty {
                guard let inv = PaymentText.nonEmpty(params["lightning"]) else { return nil }
                return lightning(scheme: scheme, invoice: inv)
            }
            return generic(scheme: scheme, network: "Bitcoin", address: address, params: params)
        case "ethereum":
            return ethereum(address: address, params: params)
        default:
            guard let known = schemes.first(where: { $0.scheme.lowercased() == scheme }), !address.isEmpty else { return nil }
            return generic(scheme: scheme, network: known.name, address: address, params: params)
        }
    }

    private static func generic(scheme: String, network: String, address: String, params: [String: String]) -> CryptoInfo {
        CryptoInfo(scheme: scheme, network: network, address: address,
                   amount: PaymentText.nonEmpty(params["amount"]),
                   label: PaymentText.nonEmpty(params["label"]),
                   message: PaymentText.nonEmpty(params["message"]))
    }

    /// EIP-681: `[pay-]target[@chainId][/function]?value=…`.
    private static func ethereum(address raw: String, params: [String: String]) -> CryptoInfo? {
        var s = Substring(raw)
        if s.lowercased().hasPrefix("pay-") { s = s.dropFirst(4) }
        if let slash = s.firstIndex(of: "/") { s = s[s.startIndex..<slash] }
        var chain: String?
        if let at = s.firstIndex(of: "@") {
            chain = PaymentText.nonEmpty(String(s[s.index(after: at)...]))
            s = s[s.startIndex..<at]
        }
        guard !s.isEmpty else { return nil }
        return CryptoInfo(scheme: "ethereum", network: "Ethereum", address: String(s),
                          amount: PaymentText.nonEmpty(params["value"] ?? params["amount"]),
                          label: PaymentText.nonEmpty(params["label"]),
                          message: PaymentText.nonEmpty(params["message"]),
                          chainID: chain)
    }

    private static func lightning(scheme: String, invoice raw: String) -> CryptoInfo {
        var invoice = raw
        if invoice.lowercased().hasPrefix("lightning:") { invoice = String(invoice.dropFirst("lightning:".count)) }
        return CryptoInfo(scheme: scheme, network: "Lightning", address: invoice,
                          amount: invoiceAmount(invoice), isLightningInvoice: true)
    }

    /// Amount in BTC from the human-readable part only: `ln` + network (`bc`, `tb`, `tbs`,
    /// `bcrt`) + optional digits + optional multiplier. The data part is never decoded.
    static func invoiceAmount(_ invoice: String) -> String? {
        let lower = invoice.lowercased()
        guard let sep = lower.lastIndex(of: "1") else { return nil }
        var hrp = Substring(lower[lower.startIndex..<sep])
        guard hrp.hasPrefix("ln") else { return nil }
        hrp = hrp.dropFirst(2)
        guard let net = ["bcrt", "tbs", "bc", "tb"].first(where: { hrp.hasPrefix($0) }) else { return nil }
        hrp = hrp.dropFirst(net.count)
        let digits = hrp.prefix(while: { $0 >= "0" && $0 <= "9" })
        let tail = hrp.dropFirst(digits.count)
        guard !digits.isEmpty, digits.count <= 19 else { return nil }
        let shift: Int
        switch tail {
        case "": shift = 0
        case "m": shift = 3
        case "u": shift = 6
        case "n": shift = 9
        case "p": shift = 12
        default: return nil
        }
        // Divide by 10^shift on the decimal text.
        var d = String(digits)
        if d.count <= shift { d = String(repeating: "0", count: shift - d.count + 1) + d }
        let cut = d.index(d.endIndex, offsetBy: -shift)
        var whole = String(d[d.startIndex..<cut])
        var frac = String(d[cut...])
        while whole.count > 1 && whole.hasPrefix("0") { whole.removeFirst() }
        while frac.hasSuffix("0") { frac.removeLast() }
        return frac.isEmpty ? whole : whole + "." + frac
    }
}
