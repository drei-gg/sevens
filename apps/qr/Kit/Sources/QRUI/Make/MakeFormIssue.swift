import Foundation

/// The form field an issue belongs to, so the message can sit under it.
enum MakeFormField: String, Hashable, Sendable {
    case link, text, ssid, wifiPassword, contactName, contactPhone, contactEmail, contactURL
    case eventTitle, eventEnd, eventURL, emailAddress, smsNumber, phone, latitude, longitude
}

/// Why a form cannot produce a `MakeRequest` yet. `isEmpty` issues are a prompt, not an error.
enum MakeFormIssue: Error, Hashable, Sendable {
    case required(MakeFormField)
    case badEmail(MakeFormField)
    case badPhone(MakeFormField)
    case badLatitude
    case badLongitude
    case endBeforeStart

    var field: MakeFormField {
        switch self {
        case .required(let f), .badEmail(let f), .badPhone(let f): return f
        case .badLatitude: return .latitude
        case .badLongitude: return .longitude
        case .endBeforeStart: return .eventEnd
        }
    }

    /// A required field that is still blank: shown as a quiet prompt, not as an error.
    var isBlank: Bool {
        if case .required = self { return true }
        return false
    }

    var message: String {
        switch self {
        case .required:
            return String(localized: "make.issue.required", defaultValue: "Fill in this field to make a code.", bundle: .module)
        case .badEmail:
            return String(localized: "make.issue.email", defaultValue: "This doesn’t look like an email address. Use the form name@example.com.", bundle: .module)
        case .badPhone:
            return String(localized: "make.issue.phone", defaultValue: "Use digits, and + ( ) - . or spaces. At least 3 digits.", bundle: .module)
        case .badLatitude:
            return String(localized: "make.issue.latitude", defaultValue: "Latitude is a number from -90 to 90.", bundle: .module)
        case .badLongitude:
            return String(localized: "make.issue.longitude", defaultValue: "Longitude is a number from -180 to 180.", bundle: .module)
        case .endBeforeStart:
            return String(localized: "make.issue.endBeforeStart", defaultValue: "The event can’t end before it starts.", bundle: .module)
        }
    }
}
