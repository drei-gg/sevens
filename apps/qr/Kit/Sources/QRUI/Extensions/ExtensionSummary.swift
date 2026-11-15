import Foundation
import QRCore

/// The one "main field" line an extension shows under a verdict (SPEC §9): the domain for
/// links, the payee for payments, the network for Wi-Fi, and so on.
struct ExtensionSummary: Equatable, Sendable {
    var label: String
    var value: String

    static let maxValueLength = 160

    static func main(for payload: Payload) -> ExtensionSummary? {
        func make(_ label: String, _ value: String?) -> ExtensionSummary? {
            guard let raw = value?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
            let text = raw.count > maxValueLength ? String(raw.prefix(maxValueLength)) + "…" : raw
            return ExtensionSummary(label: label, value: text)
        }
        switch payload {
        case .link(let i):
            return make(String(localized: "ext.field.domain", defaultValue: "Domain", bundle: .module), i.hostUnicode.isEmpty ? i.scheme : i.hostUnicode)
        case .appLink(let i):
            return make(String(localized: "ext.field.app", defaultValue: "App", bundle: .module), i.scheme)
        case .payment(let i):
            return make(String(localized: "ext.field.payee", defaultValue: "Payee", bundle: .module), i.payeeName ?? i.payeeAccount)
        case .crypto(let i):
            return make(String(localized: "ext.field.address", defaultValue: "Address", bundle: .module), i.address)
        case .wifi(let i):
            return make(String(localized: "ext.field.network", defaultValue: "Network", bundle: .module), i.ssid)
        case .contact(let i):
            let joined = [i.givenName, i.familyName].compactMap { $0 }.joined(separator: " ")
            return make(String(localized: "ext.field.name", defaultValue: "Name", bundle: .module), i.name ?? (joined.isEmpty ? i.organization : joined))
        case .event(let i):
            return make(String(localized: "ext.field.event", defaultValue: "Event", bundle: .module), i.summary)
        case .phone(let i):
            return make(String(localized: "ext.field.number", defaultValue: "Number", bundle: .module), i.number)
        case .sms(let i):
            return make(String(localized: "ext.field.number", defaultValue: "Number", bundle: .module), i.number)
        case .email(let i):
            return make(String(localized: "ext.field.address", defaultValue: "Address", bundle: .module), i.address)
        case .geo(let i):
            return make(String(localized: "ext.field.location", defaultValue: "Location", bundle: .module), "\(i.latitude), \(i.longitude)")
        case .text(let i):
            return make(String(localized: "ext.field.text", defaultValue: "Text", bundle: .module), i.text)
        case .binary(let n):
            return make(String(localized: "ext.field.size", defaultValue: "Size", bundle: .module), String(format: String(localized: "ext.field.bytes", defaultValue: "%lld bytes", bundle: .module), n))
        }
    }
}
