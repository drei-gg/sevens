import Foundation

/// vCard 2.1 / 3.0 / 4.0 (the first card in the text).
enum VCardParser {
    static func parse(_ text: String) -> ContactInfo? {
        var started = false
        var info = ContactInfo()
        var fn: String?
        for raw in ContentLines.logical(text) {
            guard let line = ContentLines.parse(raw) else { continue }
            let marker = line.rawValue.trimmingCharacters(in: .whitespaces).uppercased()
            if line.name == "BEGIN" {
                if marker == "VCARD" { started = true }
                continue
            }
            if !started { continue }
            if line.name == "END" {
                if marker == "VCARD" { break }
                continue
            }
            switch line.name {
            case "FN": if fn == nil { fn = StructuredText.nonEmpty(line.text) }
            case "N":
                let c = line.components(";")
                if info.familyName == nil { info.familyName = StructuredText.nonEmpty(c.first) }
                if info.givenName == nil, c.count > 1 { info.givenName = StructuredText.nonEmpty(c[1]) }
            case "ORG":
                if info.organization == nil { info.organization = StructuredText.nonEmpty(line.components(";").first) }
            case "TITLE": if info.title == nil { info.title = StructuredText.nonEmpty(line.text) }
            case "TEL":
                if let v = StructuredText.nonEmpty(line.text) {
                    info.phones.append(StructuredText.stripPrefix(v, "tel:").map { StructuredText.trimmed($0) } ?? v)
                }
            case "EMAIL": if let v = StructuredText.nonEmpty(line.text) { info.emails.append(v) }
            case "URL": if let v = StructuredText.nonEmpty(line.text) { info.urls.append(v) }
            case "ADR":
                let parts = line.components(";").prefix(7).compactMap { StructuredText.nonEmpty($0) }
                if !parts.isEmpty { info.addresses.append(parts.joined(separator: "\n")) }
            case "NOTE": if info.note == nil { info.note = StructuredText.nonEmpty(line.text) }
            case "BDAY": if info.birthday == nil { info.birthday = StructuredText.nonEmpty(line.text) }
            default: break
            }
        }
        guard started else { return nil }
        info.name = fn ?? StructuredText.nonEmpty(
            [info.givenName, info.familyName].compactMap { $0 }.joined(separator: " "))
        info.links = Array(info.urls.prefix(10))
        return info
    }
}
