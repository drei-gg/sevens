import Foundation

// Contract (PLAN §5.4), frozen at qr-contract-1.

public struct WiFiInfo: Sendable, Hashable {
    public var ssid: String
    /// `T` as given, uppercased ("WPA", "WEP", "SAE", "WPA2-EAP", "nopass" → "NOPASS"); nil when missing.
    public var security: String?
    public var password: String?
    public var isHidden: Bool
    /// `E` (enterprise EAP method). Non-nil means an enterprise network: no Join button.
    public var eapMethod: String?
    /// `I` (enterprise identity).
    public var identity: String?

    public init(ssid: String, security: String? = nil, password: String? = nil, isHidden: Bool = false,
                eapMethod: String? = nil, identity: String? = nil) {
        self.ssid = ssid
        self.security = security
        self.password = password
        self.isHidden = isHidden
        self.eapMethod = eapMethod
        self.identity = identity
    }

    public var isEnterprise: Bool { eapMethod != nil || (security?.contains("EAP") ?? false) }
    /// PLAN §6.2 `openWiFi`: `T` missing, empty or `nopass`.
    public var isOpen: Bool {
        guard let s = security?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return true }
        return s.lowercased() == "nopass"
    }
}

public struct ContactInfo: Sendable, Hashable {
    /// Formatted name (`FN`, or built from `N`, or MeCard `N`).
    public var name: String?
    public var givenName: String?, familyName: String?
    public var organization: String?, title: String?
    public var phones: [String]
    public var emails: [String]
    public var urls: [String]
    /// One display string per address, lines joined with "\n".
    public var addresses: [String]
    public var note: String?
    /// As written (vCard `BDAY`).
    public var birthday: String?
    /// Every link to check: `urls` plus links found in `note` (PLAN §6.2), at most 10.
    public var links: [String]

    public init(name: String? = nil, givenName: String? = nil, familyName: String? = nil,
                organization: String? = nil, title: String? = nil, phones: [String] = [],
                emails: [String] = [], urls: [String] = [], addresses: [String] = [],
                note: String? = nil, birthday: String? = nil, links: [String] = []) {
        self.name = name
        self.givenName = givenName
        self.familyName = familyName
        self.organization = organization
        self.title = title
        self.phones = phones
        self.emails = emails
        self.urls = urls
        self.addresses = addresses
        self.note = note
        self.birthday = birthday
        self.links = links
    }
}

public struct EventInfo: Sendable, Hashable {
    public var summary: String?
    /// For UTC and TZID times: the absolute instant. For floating and all-day times: the
    /// wall-clock time read as if it were GMT (format with a GMT calendar, show as local).
    public var start: Date?
    public var end: Date?
    public var isAllDay: Bool
    public var isFloating: Bool
    /// The TZID the times were given in, when known to the system.
    public var timeZoneID: String?
    public var location: String?
    public var description: String?
    public var url: String?
    /// Every link to check: `url` plus links found in description and location, at most 10.
    public var links: [String]

    public init(summary: String? = nil, start: Date? = nil, end: Date? = nil, isAllDay: Bool = false,
                isFloating: Bool = false, timeZoneID: String? = nil, location: String? = nil,
                description: String? = nil, url: String? = nil, links: [String] = []) {
        self.summary = summary
        self.start = start
        self.end = end
        self.isAllDay = isAllDay
        self.isFloating = isFloating
        self.timeZoneID = timeZoneID
        self.location = location
        self.description = description
        self.url = url
        self.links = links
    }
}

public struct PhoneInfo: Sendable, Hashable {
    public var number: String
    public init(number: String) { self.number = number }
}

public struct SMSInfo: Sendable, Hashable {
    public var number: String
    public var body: String?
    /// Links found in the body, at most 10.
    public var links: [String]
    public init(number: String, body: String? = nil, links: [String] = []) {
        self.number = number
        self.body = body
        self.links = links
    }
}

public struct EmailInfo: Sendable, Hashable {
    public var address: String
    public var subject: String?
    public var body: String?
    /// Links found in the body, at most 10.
    public var links: [String]
    public init(address: String, subject: String? = nil, body: String? = nil, links: [String] = []) {
        self.address = address
        self.subject = subject
        self.body = body
        self.links = links
    }
}

public struct GeoInfo: Sendable, Hashable {
    /// Decimal text as given.
    public var latitude: String
    public var longitude: String
    public var altitude: String?
    /// `?q=` as given (percent-decoded).
    public var query: String?
    public init(latitude: String, longitude: String, altitude: String? = nil, query: String? = nil) {
        self.latitude = latitude
        self.longitude = longitude
        self.altitude = altitude
        self.query = query
    }
}
