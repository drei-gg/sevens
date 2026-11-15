public enum UPIParser {
    /// `upi://pay` and `upi://mandate` (case-insensitive scheme), or nil.
    public static func parse(_ text: String) -> PaymentInfo? {
        guard PaymentText.withinCap(text) else { return nil }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.lowercased().hasPrefix("upi://") else { return nil }
        let rest = t.dropFirst("upi://".count)
        let hostEnd = rest.firstIndex(where: { $0 == "?" || $0 == "/" || $0 == "#" }) ?? rest.endIndex
        let kind = rest[rest.startIndex..<hostEnd].lowercased()
        guard kind == "pay" || kind == "mandate" else { return nil }
        var query: Substring = ""
        if let q = rest.firstIndex(of: "?") {
            query = rest[rest.index(after: q)...]
            if let hash = query.firstIndex(of: "#") { query = query[query.startIndex..<hash] }
        }
        // The NPCI linking spec percent-encodes spaces; `+` is not special, so it stays literal.
        let p = PaymentText.queryItems(query)
        var extra: [String: String] = ["kind": kind]
        if let v = PaymentText.nonEmpty(p["mc"]) { extra["mcc"] = v }
        if let v = PaymentText.nonEmpty(p["mode"]) { extra["mode"] = v }
        if let v = PaymentText.nonEmpty(p["url"]) { extra["url"] = v }
        return PaymentInfo(
            format: .upi,
            payeeName: PaymentText.nonEmpty(p["pn"]),
            payeeAccount: PaymentText.nonEmpty(p["pa"]),
            amount: PaymentText.nonEmpty(p["am"]),
            currency: PaymentText.nonEmpty(p["cu"])?.uppercased(),
            reference: PaymentText.nonEmpty(p["tr"]),
            note: PaymentText.nonEmpty(p["tn"]),
            party: .unknown,
            checksum: .absent,
            extra: extra)
    }
}
