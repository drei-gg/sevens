import Foundation

/// `MECARD:N:Doe,John;TEL:…;EMAIL:…;;`
enum MeCardParser {
    static func parse(body: String) -> ContactInfo? {
        var info = ContactInfo()
        var name: String?
        for (key, raw) in StructuredText.keyed(body) {
            func value() -> String { StructuredText.unescapeBackslash(raw) }
            switch key {
            case "N":
                guard name == nil, info.familyName == nil else { break }
                let parts = StructuredText.splitUnescaped(raw, ",").map { StructuredText.unescapeBackslash($0) }
                if parts.count >= 2 {
                    info.familyName = StructuredText.nonEmpty(parts[0])
                    info.givenName = StructuredText.nonEmpty(parts[1])
                } else {
                    name = StructuredText.nonEmpty(value())
                }
            case "TEL": if let v = StructuredText.nonEmpty(value()) { info.phones.append(v) }
            case "EMAIL": if let v = StructuredText.nonEmpty(value()) { info.emails.append(v) }
            case "URL": if let v = StructuredText.nonEmpty(value()) { info.urls.append(v) }
            case "ADR":
                let parts = StructuredText.splitUnescaped(raw, ",")
                    .compactMap { StructuredText.nonEmpty(StructuredText.unescapeBackslash($0)) }
                if !parts.isEmpty { info.addresses.append(parts.joined(separator: "\n")) }
            case "NOTE": if info.note == nil { info.note = StructuredText.nonEmpty(value()) }
            case "BDAY": if info.birthday == nil { info.birthday = StructuredText.nonEmpty(value()) }
            case "ORG": if info.organization == nil { info.organization = StructuredText.nonEmpty(value()) }
            case "TITLE": if info.title == nil { info.title = StructuredText.nonEmpty(value()) }
            default: break
            }
        }
        info.name = name ?? StructuredText.nonEmpty(
            [info.givenName, info.familyName].compactMap { $0 }.joined(separator: " "))
        info.links = Array(info.urls.prefix(10))
        return info
    }
}
