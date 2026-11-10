import QRCore

/// The nine kinds of code the Make screen builds (PLAN §6.8).
enum MakeFormKind: String, CaseIterable, Identifiable, Hashable, Sendable {
    case link, text, wifi, contact, event, email, sms, phone, location

    var id: String { rawValue }

    /// The kind a `MakeRequest` belongs to.
    init(request: MakeRequest) {
        switch request {
        case .link: self = .link
        case .text: self = .text
        case .wifi: self = .wifi
        case .contact: self = .contact
        case .event: self = .event
        case .email: self = .email
        case .sms: self = .sms
        case .phone: self = .phone
        case .geo: self = .location
        }
    }

    var title: String {
        switch self {
        case .link: return String(localized: "make.kind.link", defaultValue: "Web link", bundle: .module)
        case .text: return String(localized: "make.kind.text", defaultValue: "Text", bundle: .module)
        case .wifi: return String(localized: "make.kind.wifi", defaultValue: "Wi-Fi network", bundle: .module)
        case .contact: return String(localized: "make.kind.contact", defaultValue: "Contact", bundle: .module)
        case .event: return String(localized: "make.kind.event", defaultValue: "Event", bundle: .module)
        case .email: return String(localized: "make.kind.email", defaultValue: "Email", bundle: .module)
        case .sms: return String(localized: "make.kind.sms", defaultValue: "Text message", bundle: .module)
        case .phone: return String(localized: "make.kind.phone", defaultValue: "Phone number", bundle: .module)
        case .location: return String(localized: "make.kind.location", defaultValue: "Location", bundle: .module)
        }
    }

    var symbolName: String {
        switch self {
        case .link: return "link"
        case .text: return "text.alignleft"
        case .wifi: return "wifi"
        case .contact: return "person.crop.circle"
        case .event: return "calendar"
        case .email: return "envelope"
        case .sms: return "message"
        case .phone: return "phone"
        case .location: return "mappin.and.ellipse"
        }
    }

    /// Short, ASCII, used in export file names.
    var fileToken: String { rawValue }
}
