import Foundation
import QRCore

enum MakeWiFiSecurity: String, CaseIterable, Identifiable, Hashable, Sendable {
    case wpa, wep, none
    var id: String { rawValue }
    /// The `T:` value the encoder writes.
    var code: String {
        switch self {
        case .wpa: return "WPA"
        case .wep: return "WEP"
        case .none: return "nopass"
        }
    }
    var title: String {
        switch self {
        case .wpa: return String(localized: "make.wifi.wpa", defaultValue: "WPA, WPA2 or WPA3", bundle: .module)
        case .wep: return String(localized: "make.wifi.wep", defaultValue: "WEP", bundle: .module)
        case .none: return String(localized: "make.wifi.none", defaultValue: "None (open network)", bundle: .module)
        }
    }
    init(code: String?) {
        let s = (code ?? "").uppercased()
        if s.isEmpty || s == "NOPASS" { self = .none } else if s.contains("WEP") { self = .wep } else { self = .wpa }
    }
}

/// Everything the nine forms hold, as the person typed it. Pure: `result(for:)` turns it into a
/// `MakeRequest` or says what is missing. Only the fields of the chosen kind are looked at.
struct MakeFormState: Hashable, Sendable {
    var link = "", text = ""
    var ssid = "", wifiSecurity = MakeWiFiSecurity.wpa, wifiPassword = "", wifiHidden = false
    /// Set by a prefill from an enterprise network, so its EAP details survive an untouched form.
    var wifiPrefill: WiFiInfo?
    var givenName = "", familyName = "", organization = "", jobTitle = ""
    var contactPhone = "", contactEmail = "", contactURL = "", contactAddress = "", contactNote = ""
    var eventTitle = "", eventAllDay = false, eventLocation = "", eventDescription = "", eventURL = ""
    var eventStart: Date, eventEnd: Date
    var emailAddress = "", emailSubject = "", emailBody = ""
    var smsNumber = "", smsBody = ""
    var phone = ""
    var latitude = "", longitude = "", locationQuery = ""

    /// `now` seeds the event: it starts at the next full hour and lasts an hour.
    init(now: Date = Date()) {
        let hour = (now.timeIntervalSinceReferenceDate / 3600).rounded(.up) * 3600
        eventStart = Date(timeIntervalSinceReferenceDate: hour)
        eventEnd = eventStart.addingTimeInterval(3600)
    }

    enum Outcome: Equatable {
        case request(MakeRequest)
        case invalid(MakeFormIssue)
    }

    func result(for kind: MakeFormKind) -> Outcome {
        func need(_ s: String, _ f: MakeFormField) -> MakeFormIssue? {
            MakeFormValidation.trimmed(s).isEmpty ? .required(f) : nil
        }
        func nonEmpty(_ s: String) -> String? {
            let t = MakeFormValidation.trimmed(s)
            return t.isEmpty ? nil : t
        }
        switch kind {
        case .link:
            if let i = need(link, .link) { return .invalid(i) }
            return .request(.link(MakeFormValidation.trimmed(link)))
        case .text:
            if let i = need(text, .text) { return .invalid(i) }
            return .request(.text(text))
        case .wifi:
            if let i = need(ssid, .ssid) { return .invalid(i) }
            if wifiSecurity != .none, wifiPassword.isEmpty { return .invalid(.required(.wifiPassword)) }
            var info = WiFiInfo(ssid: ssid, security: wifiSecurity.code,
                                password: wifiSecurity == .none ? nil : wifiPassword, isHidden: wifiHidden)
            if let p = wifiPrefill, p.isEnterprise, MakeWiFiSecurity(code: p.security) == wifiSecurity {
                info.security = p.security
                info.eapMethod = p.eapMethod
                info.identity = p.identity
            }
            return .request(.wifi(info))
        case .contact:
            let given = nonEmpty(givenName), family = nonEmpty(familyName), org = nonEmpty(organization)
            if given == nil, family == nil, org == nil { return .invalid(.required(.contactName)) }
            if let p = nonEmpty(contactPhone), !MakeFormValidation.isPlausiblePhone(p) { return .invalid(.badPhone(.contactPhone)) }
            if let e = nonEmpty(contactEmail), !MakeFormValidation.isPlausibleEmail(e) { return .invalid(.badEmail(.contactEmail)) }
            let full = [given, family].compactMap { $0 }.joined(separator: " ")
            return .request(.contact(ContactInfo(
                name: full.isEmpty ? nil : full, givenName: given, familyName: family,
                organization: org, title: nonEmpty(jobTitle),
                phones: nonEmpty(contactPhone).map { [$0] } ?? [],
                emails: nonEmpty(contactEmail).map { [$0] } ?? [],
                urls: nonEmpty(contactURL).map { [$0] } ?? [],
                addresses: nonEmpty(contactAddress).map { [$0] } ?? [],
                note: nonEmpty(contactNote))))
        case .event:
            if let i = need(eventTitle, .eventTitle) { return .invalid(i) }
            if eventEnd < eventStart { return .invalid(.endBeforeStart) }
            return .request(.event(eventInfo()))
        case .email:
            if let i = need(emailAddress, .emailAddress) { return .invalid(i) }
            if !MakeFormValidation.isPlausibleEmail(emailAddress) { return .invalid(.badEmail(.emailAddress)) }
            return .request(.email(EmailInfo(address: MakeFormValidation.trimmed(emailAddress),
                                             subject: nonEmpty(emailSubject), body: nonEmpty(emailBody))))
        case .sms:
            if let i = need(smsNumber, .smsNumber) { return .invalid(i) }
            if !MakeFormValidation.isPlausiblePhone(smsNumber) { return .invalid(.badPhone(.smsNumber)) }
            return .request(.sms(SMSInfo(number: MakeFormValidation.trimmed(smsNumber), body: nonEmpty(smsBody))))
        case .phone:
            if let i = need(phone, .phone) { return .invalid(i) }
            if !MakeFormValidation.isPlausiblePhone(phone) { return .invalid(.badPhone(.phone)) }
            return .request(.phone(MakeFormValidation.trimmed(phone)))
        case .location:
            if let i = need(latitude, .latitude) { return .invalid(i) }
            guard let lat = MakeFormValidation.number(latitude), (-90...90).contains(lat) else { return .invalid(.badLatitude) }
            if let i = need(longitude, .longitude) { return .invalid(i) }
            guard let lon = MakeFormValidation.number(longitude), (-180...180).contains(lon) else { return .invalid(.badLongitude) }
            return .request(.geo(GeoInfo(latitude: MakeFormValidation.normalizedNumber(latitude),
                                         longitude: MakeFormValidation.normalizedNumber(longitude),
                                         altitude: nil, query: nonEmpty(locationQuery))))
        }
    }

    /// Timed events are absolute instants (written in UTC). All-day events carry the local
    /// calendar date read as GMT, and an exclusive end date (iCalendar's rule), the way
    /// `EventInfo` documents its all-day dates.
    private func eventInfo() -> EventInfo {
        func nonEmpty(_ s: String) -> String? {
            let t = MakeFormValidation.trimmed(s)
            return t.isEmpty ? nil : t
        }
        var start = eventStart, end: Date? = eventEnd
        if eventAllDay {
            start = Self.wallAsGMT(eventStart)
            end = Self.wallAsGMT(eventEnd).addingTimeInterval(86_400)
        }
        return EventInfo(summary: nonEmpty(eventTitle), start: start, end: end, isAllDay: eventAllDay,
                         location: nonEmpty(eventLocation), description: nonEmpty(eventDescription), url: nonEmpty(eventURL))
    }

    static func wallAsGMT(_ date: Date, calendar: Calendar = .current) -> Date {
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        var gmt = Calendar(identifier: .gregorian)
        gmt.timeZone = TimeZone(identifier: "GMT") ?? .gmt
        return gmt.date(from: c) ?? date
    }

    static func localFromGMTWall(_ date: Date, calendar: Calendar = .current) -> Date {
        var gmt = Calendar(identifier: .gregorian)
        gmt.timeZone = TimeZone(identifier: "GMT") ?? .gmt
        let c = gmt.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        return calendar.date(from: c) ?? date
    }

    // MARK: Prefill

    /// A form holding what `request` says (from a result's "Make code" action).
    init(prefill request: MakeRequest, now: Date = Date()) {
        self.init(now: now)
        switch request {
        case .link(let s): link = s
        case .text(let s): text = s
        case .wifi(let w):
            ssid = w.ssid
            wifiSecurity = MakeWiFiSecurity(code: w.security)
            wifiPassword = w.password ?? ""
            wifiHidden = w.isHidden
            if w.isEnterprise { wifiPrefill = w }
        case .contact(let c):
            givenName = c.givenName ?? ""
            familyName = c.familyName ?? ""
            if givenName.isEmpty, familyName.isEmpty, let n = c.name { givenName = n }
            organization = c.organization ?? ""
            jobTitle = c.title ?? ""
            contactPhone = c.phones.first ?? ""
            contactEmail = c.emails.first ?? ""
            contactURL = c.urls.first ?? ""
            contactAddress = c.addresses.first ?? ""
            contactNote = c.note ?? ""
        case .event(let e):
            eventTitle = e.summary ?? ""
            eventAllDay = e.isAllDay
            let wall = e.isAllDay || e.isFloating
            if let s = e.start { eventStart = wall ? Self.localFromGMTWall(s) : s }
            if let en = e.end {
                // All-day ends are exclusive; show the last day.
                let shown = e.isAllDay ? en.addingTimeInterval(-86_400) : en
                eventEnd = wall ? Self.localFromGMTWall(shown) : shown
            } else {
                eventEnd = eventStart.addingTimeInterval(3600)
            }
            if eventEnd < eventStart { eventEnd = eventStart }
            eventLocation = e.location ?? ""
            eventDescription = e.description ?? ""
            eventURL = e.url ?? ""
        case .email(let e):
            emailAddress = e.address
            emailSubject = e.subject ?? ""
            emailBody = e.body ?? ""
        case .sms(let s):
            smsNumber = s.number
            smsBody = s.body ?? ""
        case .phone(let n): phone = n
        case .geo(let g):
            latitude = g.latitude
            longitude = g.longitude
            locationQuery = g.query ?? ""
        }
    }
}
