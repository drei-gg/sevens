public enum SwissQRParser {
    /// Swiss QR-bill (first line `SPC`), or nil. IBAN, QRR mod-10 or SCOR mod-97 failure → `.invalid`.
    public static func parse(_ text: String) -> PaymentInfo? {
        guard PaymentText.withinCap(text) else { return nil }
        let l = PaymentText.lines(text)
        func line(_ i: Int) -> String? { i < l.count ? PaymentText.nonEmpty(l[i]) : nil }
        guard line(0) == "SPC" else { return nil }
        guard let version = line(1), version.utf8.count == 4, version.hasPrefix("02"),
              version.utf8.allSatisfy({ (48...57).contains($0) }) else { return nil }
        if let coding = line(2), coding != "1" { return nil }

        var info = PaymentInfo(format: .swiss)
        var results: [Bool] = []
        if let raw = line(3) {
            let iban = raw.filter { $0 != " " }.uppercased()
            info.payeeAccount = iban
            results.append(IBAN.isValid(iban))
        }
        // Creditor: type, name, then address lines 6-9 (S: street, number, postcode, town; K: two free lines).
        info.payeeName = line(5)
        let address = (6...9).compactMap(line)
        if !address.isEmpty { info.extra["creditorAddress"] = address.joined(separator: "\n") }
        if line(4) == "S" { info.city = line(9) }
        info.country = line(10)
        // Lines 11-17 (ultimate creditor) are not used.
        info.amount = line(18)
        info.currency = line(19)?.uppercased()
        if let name = line(21) { info.extra["debtorName"] = name }
        let refType = line(27)?.uppercased()
        if let refType { info.extra["referenceType"] = refType }
        if let ref = line(28) {
            info.reference = ref
            let compact = ref.filter { $0 != " " }
            if refType == "QRR" { results.append(isValidQRR(compact)) }
            if refType == "SCOR" { results.append(isValidCreditorReference(compact)) }
        }
        info.note = line(29)
        if let v = line(31) { info.extra["billingInfo"] = v }
        info.checksum = results.isEmpty ? .absent : (results.allSatisfy { $0 } ? .valid : .invalid)
        return info
    }

    /// 27 digits; the last is the recursive mod-10 check digit of the first 26.
    static func isValidQRR(_ ref: String) -> Bool {
        let d = Array(ref.utf8)
        guard d.count == 27, d.allSatisfy({ (48...57).contains($0) }) else { return false }
        let table = [0, 9, 4, 6, 8, 2, 7, 1, 3, 5]
        var carry = 0
        for b in d.prefix(26) { carry = table[(carry + Int(b) - 48) % 10] }
        return (10 - carry) % 10 == Int(d[26]) - 48
    }

    /// ISO 11649 creditor reference: `RF`, two check digits, up to 21 alphanumerics; mod-97 equals 1.
    static func isValidCreditorReference(_ ref: String) -> Bool {
        let s = ref.uppercased()
        let u = Array(s.utf8)
        guard (5...25).contains(u.count), s.hasPrefix("RF"),
              u[2...3].allSatisfy({ (48...57).contains($0) }),
              u.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) }) else { return false }
        return IBAN.mod97Is1(Array(u.dropFirst(4)) + Array(u.prefix(4)))
    }
}
