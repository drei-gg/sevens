// Contract (PLAN §5.4), frozen at qr-contract-1.

public enum Payload: Sendable, Hashable {
    /// http/https, including D9 schemeless links. Also the dangerous schemes of PLAN §6.2
    /// (`javascript:`, `data:`, …), which carry `dangerousScheme`; their LinkInfo has an
    /// empty host and everything after the colon in `pathAndQuery`.
    case link(LinkInfo)
    /// Any other scheme not listed below (carries `unknownScheme`).
    case appLink(AppLinkInfo)
    /// EMVCo, UPI, EPC, Swiss.
    case payment(PaymentInfo)
    /// Crypto URIs and bare addresses.
    case crypto(CryptoInfo)
    case wifi(WiFiInfo)
    case contact(ContactInfo)
    case event(EventInfo)
    case phone(PhoneInfo), sms(SMSInfo), email(EmailInfo), geo(GeoInfo)
    case text(TextInfo)
    case binary(byteCount: Int)

    /// "link", "payment", … used by fixtures and history.
    public var typeID: String {
        switch self {
        case .link: return "link"
        case .appLink: return "appLink"
        case .payment: return "payment"
        case .crypto: return "crypto"
        case .wifi: return "wifi"
        case .contact: return "contact"
        case .event: return "event"
        case .phone: return "phone"
        case .sms: return "sms"
        case .email: return "email"
        case .geo: return "geo"
        case .text: return "text"
        case .binary: return "binary"
        }
    }

    /// Flat view, used by fixtures and CSV export. Keys are listed in `PayloadFields.swift`;
    /// nil values are omitted, booleans are "true"/"false", lists are joined with "\n".
    public var fields: [String: String] {
        switch self {
        case .link(let i): return i.fields
        case .appLink(let i): return i.fields
        case .payment(let i): return i.fields
        case .crypto(let i): return i.fields
        case .wifi(let i): return i.fields
        case .contact(let i): return i.fields
        case .event(let i): return i.fields
        case .phone(let i): return i.fields
        case .sms(let i): return i.fields
        case .email(let i): return i.fields
        case .geo(let i): return i.fields
        case .text(let i): return i.fields
        case .binary(let n): return ["byteCount": String(n)]
        }
    }
}
