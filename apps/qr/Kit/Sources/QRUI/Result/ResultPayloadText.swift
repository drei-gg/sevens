import Foundation
import QRCore

/// The text that Copy and Share carry, per payload.
enum ResultPayloadText {
    /// What the Copy button copies: the thing a person would paste (the address, the number, the text).
    static func copyText(for analysis: Analysis) -> String? {
        switch analysis.payload {
        case .link(let info): return info.original
        case .appLink(let info): return info.original
        case .payment(let info): return paymentDetails(info)
        case .crypto(let info): return info.address
        case .wifi(let info): return info.password
        case .phone(let info): return info.number
        case .email(let info): return info.address
        case .geo(let info): return info.latitude + "," + info.longitude
        case .text(let info): return info.text
        case .contact, .event, .sms, .binary: return analysis.code.text
        }
    }

    /// What Share sends: the scanned text itself.
    static func shareText(for analysis: Analysis) -> String? {
        switch analysis.payload {
        case .crypto(let info): return info.address
        case .binary: return nil
        default: return analysis.code.text
        }
    }

    /// "Copy details" for a payment: one `label: value` line per fact the code states.
    static func paymentDetails(_ info: PaymentInfo) -> String {
        var lines: [String] = []
        func add(_ label: String, _ value: String?) {
            if let value, !value.isEmpty { lines.append(label + ": " + value) }
        }
        add(String(localized: "result.payment.payee", defaultValue: "Pays", bundle: .module), info.payeeName)
        add(String(localized: "result.payment.account", defaultValue: "Account", bundle: .module), info.payeeAccount)
        if let amount = info.amount, !amount.isEmpty {
            add(String(localized: "result.payment.amount", defaultValue: "Amount", bundle: .module),
                [info.currency, amount].compactMap { $0 }.joined(separator: " "))
        }
        add(String(localized: "result.payment.network", defaultValue: "Network", bundle: .module), info.network)
        add(String(localized: "result.payment.city", defaultValue: "City", bundle: .module), info.city)
        add(String(localized: "result.payment.country", defaultValue: "Country", bundle: .module), info.country)
        add(String(localized: "result.payment.reference", defaultValue: "Reference", bundle: .module), info.reference)
        add(String(localized: "result.payment.note", defaultValue: "Note", bundle: .module), info.note)
        for key in info.extra.keys.sorted() { add(key, info.extra[key]) }
        return lines.joined(separator: "\n")
    }
}
