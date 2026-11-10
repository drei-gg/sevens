public enum EPCParser {
    /// EPC069-12 (first line `BCD`), or nil. IBAN mod-97 failure → checksum `.invalid`.
    public static func parse(_ text: String) -> PaymentInfo? {
        guard PaymentText.withinCap(text) else { return nil }
        let l = PaymentText.lines(text)
        func line(_ i: Int) -> String? { i < l.count ? PaymentText.nonEmpty(l[i]) : nil }
        guard line(0) == "BCD" else { return nil }
        if let id = line(3), id != "SCT" { return nil }
        var info = PaymentInfo(format: .epc)
        if let v = line(4) { info.extra["bic"] = v }
        info.payeeName = line(5)
        if let raw = line(6) {
            let iban = raw.filter { $0 != " " }.uppercased()
            info.payeeAccount = iban
            info.checksum = IBAN.isValid(iban) ? .valid : .invalid
        }
        if let v = line(7) {
            let letters = v.prefix(3)
            if letters.count == 3, letters.allSatisfy({ $0.isASCII && $0.isLetter }) {
                info.currency = letters.uppercased()
                info.amount = PaymentText.nonEmpty(String(v.dropFirst(3)))
            } else {
                info.amount = v
            }
        }
        if let v = line(8) { info.extra["purpose"] = v }
        info.reference = line(9)
        info.note = line(10)
        if let v = line(11) { info.extra["information"] = v }
        return info
    }
}
