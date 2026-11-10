import Foundation

// Contract (PLAN §5.4), frozen at qr-contract-1.
// The flat `fields` view of every payload. These keys are what fixtures' "fields" match
// against and what the CSV export writes. nil values are omitted; booleans are "true"/"false";
// lists are joined with "\n"; dates are ISO 8601 (see `EventInfo`).

extension Dictionary where Key == String, Value == String {
    fileprivate mutating func put(_ key: String, _ value: String?) {
        if let value { self[key] = value }
    }
    fileprivate mutating func put(_ key: String, _ value: Bool?) {
        if let value { self[key] = value ? "true" : "false" }
    }
    fileprivate mutating func put(_ key: String, _ values: [String]) {
        if !values.isEmpty { self[key] = values.joined(separator: "\n") }
    }
}

extension LinkInfo {
    public var fields: [String: String] {
        var f: [String: String] = [:]
        f.put("original", original)
        f.put("scheme", scheme)
        f.put("userinfo", userinfo)
        f.put("host", hostUnicode)
        f.put("hostASCII", hostASCII)
        f.put("registrableDomain", registrableDomain)
        f.put("publicSuffix", publicSuffix)
        f.put("suffixIsPrivate", suffixIsPrivate)
        f.put("port", port.map(String.init))
        f.put("pathAndQuery", pathAndQuery)
        f.put("isIPAddress", isIPAddress)
        f.put("normalized", normalized)
        return f
    }
}

extension AppLinkInfo {
    public var fields: [String: String] { ["scheme": scheme, "original": original] }
}

extension PaymentInfo {
    public var fields: [String: String] {
        var f: [String: String] = [:]
        f.put("format", format.rawValue)
        f.put("payeeName", payeeName)
        f.put("payeeAccount", payeeAccount)
        f.put("city", city)
        f.put("country", country)
        f.put("amount", amount)
        f.put("currency", currency)
        f.put("network", network)
        f.put("isDynamic", isDynamic)
        f.put("reference", reference)
        f.put("note", note)
        f.put("party", party.rawValue)
        f.put("checksum", checksum.rawValue)
        for (k, v) in extra { f["extra." + k] = v }
        return f
    }
}

extension CryptoInfo {
    public var fields: [String: String] {
        var f: [String: String] = [:]
        f.put("scheme", scheme)
        f.put("network", network)
        f.put("address", address)
        f.put("amount", amount)
        f.put("label", label)
        f.put("message", message)
        f.put("chainID", chainID)
        f.put("isLightningInvoice", isLightningInvoice)
        return f
    }
}

extension WiFiInfo {
    public var fields: [String: String] {
        var f: [String: String] = [:]
        f.put("ssid", ssid)
        f.put("security", security)
        f.put("password", password)
        f.put("hidden", isHidden)
        f.put("eapMethod", eapMethod)
        f.put("identity", identity)
        return f
    }
}

extension ContactInfo {
    public var fields: [String: String] {
        var f: [String: String] = [:]
        f.put("name", name)
        f.put("givenName", givenName)
        f.put("familyName", familyName)
        f.put("organization", organization)
        f.put("title", title)
        f.put("phones", phones)
        f.put("emails", emails)
        f.put("urls", urls)
        f.put("addresses", addresses)
        f.put("note", note)
        f.put("birthday", birthday)
        return f
    }
}

/// ISO 8601 for `EventInfo` fields: "2026-11-10" (all-day), "2026-11-10T09:00:00" (floating),
/// "2026-11-10T01:00:00Z" (absolute).
enum EventDateText {
    static func format(_ date: Date, allDay: Bool, floating: Bool) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "GMT") ?? cal.timeZone
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        func p(_ n: Int?, _ w: Int = 2) -> String {
            let s = String(n ?? 0)
            return String(repeating: "0", count: max(0, w - s.count)) + s
        }
        let day = "\(p(c.year, 4))-\(p(c.month))-\(p(c.day))"
        if allDay { return day }
        let time = "\(day)T\(p(c.hour)):\(p(c.minute)):\(p(c.second))"
        return floating ? time : time + "Z"
    }
}

extension EventInfo {
    public var fields: [String: String] {
        var f: [String: String] = [:]
        f.put("summary", summary)
        f.put("start", start.map { EventDateText.format($0, allDay: isAllDay, floating: isFloating) })
        f.put("end", end.map { EventDateText.format($0, allDay: isAllDay, floating: isFloating) })
        f.put("allDay", isAllDay)
        f.put("floating", isFloating)
        f.put("timeZone", timeZoneID)
        f.put("location", location)
        f.put("description", description)
        f.put("url", url)
        return f
    }
}

extension PhoneInfo {
    public var fields: [String: String] { ["number": number] }
}

extension SMSInfo {
    public var fields: [String: String] {
        var f: [String: String] = ["number": number]
        f.put("body", body)
        return f
    }
}

extension EmailInfo {
    public var fields: [String: String] {
        var f: [String: String] = ["address": address]
        f.put("subject", subject)
        f.put("body", body)
        return f
    }
}

extension GeoInfo {
    public var fields: [String: String] {
        var f: [String: String] = ["latitude": latitude, "longitude": longitude]
        f.put("altitude", altitude)
        f.put("query", query)
        return f
    }
}

extension TextInfo {
    public var fields: [String: String] {
        var f: [String: String] = ["text": text]
        f.put("truncated", isTruncated)
        return f
    }
}
