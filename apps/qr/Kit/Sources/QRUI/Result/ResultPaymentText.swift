import Foundation
import QRCore

/// The words of a payment result (SPEC §5), as pure functions: which fields are first-class rows
/// for each format, and a human label for every key the core puts in `PaymentInfo.extra`.
enum ResultPaymentText {
    struct Row: Hashable, Sendable {
        let label: String
        let value: String
    }

    /// D11's fallback when the network doesn't say person or business. Exactly as the spec words it.
    static var checkMatch: String {
        String(localized: "result.payment.checkMatch", defaultValue: "Check this matches where you are.", bundle: .module)
    }

    /// The account row's label: an IBAN for SEPA and Swiss QR-bill, a payee address (UPI ID)
    /// for UPI, the account the network names for EMVCo.
    static func accountLabel(_ format: PaymentFormat) -> String {
        switch format {
        case .epc, .swiss: return String(localized: "result.payment.iban", defaultValue: "IBAN", bundle: .module)
        case .upi: return String(localized: "result.payment.payeeAddress", defaultValue: "Payee address", bundle: .module)
        case .emv: return String(localized: "result.payment.account", defaultValue: "Account", bundle: .module)
        }
    }

    /// The first-class rows under the payee's name and the amount, in SPEC §5's order for the
    /// format: city, country, network, account (IBAN or payee address), reference, note. Empty
    /// values are left out; the amount, static/dynamic and checksum are drawn by the view.
    static func rows(for info: PaymentInfo) -> [Row] {
        var rows: [Row] = []
        func add(_ label: String, _ value: String?) {
            if let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty {
                rows.append(Row(label: label, value: value))
            }
        }
        add(String(localized: "result.payment.city", defaultValue: "City", bundle: .module), info.city)
        add(String(localized: "result.payment.country", defaultValue: "Country", bundle: .module), country(info.country))
        add(String(localized: "result.payment.network", defaultValue: "Network", bundle: .module), info.network)
        add(accountLabel(info.format), info.payeeAccount)
        add(String(localized: "result.payment.reference", defaultValue: "Reference", bundle: .module), info.reference)
        add(String(localized: "result.payment.note", defaultValue: "Note", bundle: .module), info.note)
        return rows
    }

    /// Everything else, for More details: the format, then every extra the core found, each with
    /// a human label, never a raw key. `alternateName` is shown under the payee instead.
    static func moreDetails(for info: PaymentInfo) -> [Row] {
        var rows = [Row(label: String(localized: "result.payment.format", defaultValue: "Format", bundle: .module),
                        value: formatName(info.format))]
        for key in info.extra.keys.sorted(by: { order($0) < order($1) }) where key != "alternateName" {
            guard let raw = info.extra[key]?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { continue }
            rows.append(Row(label: label(forExtra: key), value: value(forExtra: key, raw)))
        }
        return rows
    }

    static func formatName(_ format: PaymentFormat) -> String {
        switch format {
        case .emv: return String(localized: "result.payment.format.emv", defaultValue: "EMVCo merchant QR", bundle: .module)
        case .upi: return String(localized: "result.payment.format.upi", defaultValue: "UPI", bundle: .module)
        case .epc: return String(localized: "result.payment.format.epc", defaultValue: "SEPA credit transfer (EPC)", bundle: .module)
        case .swiss: return String(localized: "result.payment.format.swiss", defaultValue: "Swiss QR-bill", bundle: .module)
        }
    }

    /// "PH" → "Philippines (PH)"; anything the system doesn't know stays as written.
    static func country(_ code: String?) -> String? {
        guard let code = code?.trimmingCharacters(in: .whitespacesAndNewlines), !code.isEmpty else { return nil }
        guard code.count == 2, let name = Locale.current.localizedString(forRegionCode: code.uppercased()) else { return code }
        let upper = code.uppercased()
        return String(localized: "result.payment.countryName", defaultValue: "\(name) (\(upper))", bundle: .module,
                      comment: "A payment code’s country. First: the country’s name. Second: its two-letter code.")
    }

    /// A human label for every key `apps/qr/core/src/payments.rs` writes to `extra`. An unknown
    /// key (a newer core) still reads as words: "additional.12" → "Additional data 12",
    /// "someKey" → "Some key".
    static func label(forExtra key: String) -> String {
        switch key {
        case "mcc": return String(localized: "result.payment.extra.mcc", defaultValue: "Merchant category code", bundle: .module)
        case "currencyNumeric": return String(localized: "result.payment.extra.currencyNumeric", defaultValue: "Currency code", bundle: .module)
        case "networkID": return String(localized: "result.payment.extra.networkID", defaultValue: "Network identifier", bundle: .module)
        case "alternateName": return String(localized: "result.payment.extra.alternateName", defaultValue: "Name in another language", bundle: .module)
        case "alternateCity": return String(localized: "result.payment.extra.alternateCity", defaultValue: "City in another language", bundle: .module)
        case "alternateLanguage": return String(localized: "result.payment.extra.alternateLanguage", defaultValue: "Other language", bundle: .module)
        case "bic": return String(localized: "result.payment.extra.bic", defaultValue: "Bank (BIC)", bundle: .module)
        case "purpose": return String(localized: "result.payment.extra.purpose", defaultValue: "Purpose code", bundle: .module)
        case "information": return String(localized: "result.payment.extra.information", defaultValue: "Message to the payer", bundle: .module)
        case "creditorAddress": return String(localized: "result.payment.extra.creditorAddress", defaultValue: "Payee’s address", bundle: .module)
        case "debtorName": return String(localized: "result.payment.extra.debtorName", defaultValue: "Payer", bundle: .module)
        case "referenceType": return String(localized: "result.payment.extra.referenceType", defaultValue: "Reference type", bundle: .module)
        case "billingInfo": return String(localized: "result.payment.extra.billingInfo", defaultValue: "Billing information", bundle: .module)
        case "kind": return String(localized: "result.payment.extra.kind", defaultValue: "Request", bundle: .module)
        case "mode": return String(localized: "result.payment.extra.mode", defaultValue: "Initiation mode", bundle: .module)
        case "url": return String(localized: "result.payment.extra.url", defaultValue: "Link", bundle: .module)
        default: break
        }
        let additional = "additional."
        if key.hasPrefix(additional) {
            let tag = String(key.dropFirst(additional.count))
            switch tag {
            // EMVCo merchant-presented QR, ID 62 (Additional Data Field Template).
            case "01": return String(localized: "result.payment.extra.bill", defaultValue: "Bill number", bundle: .module)
            case "02": return String(localized: "result.payment.extra.mobile", defaultValue: "Mobile number", bundle: .module)
            case "03": return String(localized: "result.payment.extra.store", defaultValue: "Store", bundle: .module)
            case "04": return String(localized: "result.payment.extra.loyalty", defaultValue: "Loyalty number", bundle: .module)
            case "05": return String(localized: "result.payment.reference", defaultValue: "Reference", bundle: .module)
            case "06": return String(localized: "result.payment.extra.customer", defaultValue: "Customer label", bundle: .module)
            case "07": return String(localized: "result.payment.extra.terminal", defaultValue: "Terminal", bundle: .module)
            case "08": return String(localized: "result.payment.extra.purposeText", defaultValue: "Purpose", bundle: .module)
            case "09": return String(localized: "result.payment.extra.consumerData", defaultValue: "Details the payer is asked for", bundle: .module)
            case "10": return String(localized: "result.payment.extra.taxID", defaultValue: "Merchant tax ID", bundle: .module)
            case "11": return String(localized: "result.payment.extra.channel", defaultValue: "Merchant channel", bundle: .module)
            default:
                return String(localized: "result.payment.extra.additional", defaultValue: "Additional data \(tag)", bundle: .module,
                              comment: "A payment code field we have no name for. The number is the field’s tag.")
            }
        }
        return readable(key)
    }

    /// Values that are codes get words where the code is a fixed vocabulary.
    static func value(forExtra key: String, _ raw: String) -> String {
        switch (key, raw.lowercased()) {
        case ("kind", "pay"): return String(localized: "result.payment.kind.pay", defaultValue: "One payment", bundle: .module)
        case ("kind", "mandate"): return String(localized: "result.payment.kind.mandate", defaultValue: "Mandate (repeating payments)", bundle: .module)
        case ("referenceType", "qrr"): return String(localized: "result.payment.refType.qrr", defaultValue: "QR reference", bundle: .module)
        case ("referenceType", "scor"): return String(localized: "result.payment.refType.scor", defaultValue: "Creditor reference", bundle: .module)
        case ("referenceType", "non"): return String(localized: "result.payment.refType.non", defaultValue: "No reference", bundle: .module)
        default: return raw
        }
    }

    /// "debtorName" → "Debtor name"; "some_key.x" → "Some key x". Never a dotted or camel-cased key.
    static func readable(_ key: String) -> String {
        var words: [String] = []
        var current = ""
        for ch in key {
            if ch == "." || ch == "_" || ch == "-" || ch == " " {
                if !current.isEmpty { words.append(current); current = "" }
            } else if ch.isUppercase, let last = current.last, !last.isUppercase {
                words.append(current); current = String(ch)
            } else {
                current.append(ch)
            }
        }
        if !current.isEmpty { words.append(current) }
        let joined = words.map { $0.count > 1 && $0 == $0.uppercased() ? $0 : $0.lowercased() }.joined(separator: " ")
        guard let first = joined.first else {
            return String(localized: "result.payment.extra.other", defaultValue: "Other detail", bundle: .module)
        }
        return first.uppercased() + joined.dropFirst()
    }

    /// Known keys first in a fixed order, then the rest alphabetically.
    private static let knownOrder = ["kind", "mode", "url", "networkID", "mcc", "currencyNumeric", "bic", "purpose", "information",
                                     "creditorAddress", "debtorName", "referenceType", "billingInfo", "alternateCity", "alternateLanguage"]

    private static func order(_ key: String) -> String {
        if let index = knownOrder.firstIndex(of: key) { return String(format: "0%03d", index) }
        return "1" + key
    }
}
