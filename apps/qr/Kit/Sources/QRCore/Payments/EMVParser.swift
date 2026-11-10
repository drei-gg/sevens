// WP 1.3 owns this file. The signature below is the seam the Classifier (WP 2.1) calls; keep it.

public enum EMVParser {
    static let maxBytes = 8 * 1024

    /// An EMVCo merchant-presented payload (SPEC §5), or nil if it isn't one.
    /// `checksum` is `.invalid` on a CRC mismatch; the Analyzer turns that into `paymentChecksumFail`.
    public static func parse(_ text: String, networks: [EMVNetwork], currencies: [String: String]) -> PaymentInfo? {
        let bytes = Array(text.utf8)
        guard bytes.starts(with: Array("000201".utf8)) else { return nil }
        var info = PaymentInfo(format: .emv)
        guard bytes.count <= maxBytes else {
            info.checksum = .invalid
            return info
        }
        info.checksum = checksum(bytes)

        let (items, unit) = topLevel(text)
        for item in items {
            apply(item, unit: unit, to: &info, networks: networks, currencies: currencies)
        }
        // First matching network wins, regardless of template order.
        applyNetwork(items, unit: unit, to: &info, networks: networks)
        return info
    }

    // MARK: CRC

    private static func checksum(_ bytes: [UInt8]) -> PaymentInfo.Checksum {
        let marker = Array("6304".utf8)
        guard bytes.count >= 8, Array(bytes[(bytes.count - 8)..<(bytes.count - 4)]) == marker else { return .absent }
        let given = bytes[(bytes.count - 4)...]
        var value: UInt16 = 0
        for b in given {
            let d: UInt16
            switch b {
            case 0x30...0x39: d = UInt16(b - 0x30)
            case 0x41...0x46: d = UInt16(b - 0x41 + 10)
            case 0x61...0x66: d = UInt16(b - 0x61 + 10)
            default: return .invalid
            }
            value = value << 4 | d
        }
        return CRC16.ccittFalse(bytes[..<(bytes.count - 4)]) == value ? .valid : .invalid
    }

    // MARK: TLV

    private static func topLevel(_ text: String) -> ([EMVTLVItem], EMVTLV.Unit) {
        var lenient: [EMVTLVItem] = []
        for unit in [EMVTLV.Unit.utf8, .utf16] {
            let result = EMVTLV.read(EMVTLV.units(of: text, unit))
            if unit == .utf8 { lenient = result.items }
            if result.complete, result.items.last?.tag == "63" { return (result.items, unit) }
        }
        return (lenient, .utf8)
    }

    private static func subItems(_ item: EMVTLVItem) -> [EMVTLVItem] {
        EMVTLV.read(item.value).items
    }

    // MARK: Fields

    private static func apply(_ item: EMVTLVItem, unit: EMVTLV.Unit, to info: inout PaymentInfo,
                              networks: [EMVNetwork], currencies: [String: String]) {
        let value = unit.text(item.value)
        guard let tag = Int(item.tag) else { return }
        switch tag {
        case 1:
            info.isDynamic = value == "11" ? false : value == "12" ? true : nil
        case 26...51:
            break // handled by applyNetwork
        case 52: info.extra["mcc"] = value
        case 53:
            if let alpha = Currency.alpha(numeric: value, table: currencies) {
                info.currency = alpha
            } else {
                info.extra["currencyNumeric"] = value
            }
        case 54: info.amount = value
        case 58: info.country = value
        case 59: info.payeeName = value
        case 60: info.city = value
        case 62:
            for sub in subItems(item) {
                let v = unit.text(sub.value)
                switch sub.tag {
                case "05": info.reference = v
                case "08": info.note = v
                default: info.extra["additional.\(sub.tag)"] = v
                }
            }
        case 64:
            for sub in subItems(item) {
                let v = unit.text(sub.value)
                switch sub.tag {
                case "00": info.extra["alternateLanguage"] = v
                case "01": info.extra["alternateName"] = v
                case "02": info.extra["alternateCity"] = v
                default: break
                }
            }
        default:
            break
        }
    }

    private static func applyNetwork(_ items: [EMVTLVItem], unit: EMVTLV.Unit, to info: inout PaymentInfo,
                                     networks: [EMVNetwork]) {
        var firstID: String?
        for item in items {
            guard let tag = Int(item.tag), (26...51).contains(tag) else { continue }
            let subs = subItems(item)
            guard let idItem = subs.first(where: { $0.tag == "00" }) else { continue }
            let id = unit.text(idItem.value)
            if firstID == nil { firstID = id }
            let lowered = id.lowercased()
            guard let net = networks.first(where: { $0.id.lowercased() == lowered }) else { continue }
            info.network = net.name
            info.party = PaymentInfo.Party(rawValue: net.party) ?? .unknown
            let account = subs.first(where: { $0.tag == "01" }) ?? subs.first(where: { $0.tag == "02" })
            info.payeeAccount = account.map { unit.text($0.value) }
            return
        }
        if let firstID { info.extra["networkID"] = firstID }
    }
}
