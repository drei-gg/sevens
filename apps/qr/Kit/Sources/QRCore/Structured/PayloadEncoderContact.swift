import Foundation

extension PayloadEncoder {
    /// vCard 3.0, CRLF line endings, lines folded at 75 octets.
    static func vCard(_ c: ContactInfo) -> String {
        let esc = StructuredText.escapeText
        var lines = ["BEGIN:VCARD", "VERSION:3.0"]
        lines.append("N:" + esc(c.familyName ?? "") + ";" + esc(c.givenName ?? "") + ";;;")
        let fn = StructuredText.nonEmpty(c.name)
            ?? StructuredText.nonEmpty([c.givenName, c.familyName].compactMap { $0 }.joined(separator: " "))
            ?? StructuredText.nonEmpty(c.organization)
        if let fn { lines.append("FN:" + esc(fn)) }
        if let o = StructuredText.nonEmpty(c.organization) { lines.append("ORG:" + esc(o)) }
        if let t = StructuredText.nonEmpty(c.title) { lines.append("TITLE:" + esc(t)) }
        for p in c.phones where !p.isEmpty { lines.append("TEL:" + esc(p)) }
        for e in c.emails where !e.isEmpty { lines.append("EMAIL:" + esc(e)) }
        for u in c.urls where !u.isEmpty { lines.append("URL:" + esc(u)) }
        for a in c.addresses where !a.isEmpty { lines.append("ADR:;;" + esc(a) + ";;;;") }
        if let n = StructuredText.nonEmpty(c.note) { lines.append("NOTE:" + esc(n)) }
        if let b = StructuredText.nonEmpty(c.birthday) { lines.append("BDAY:" + esc(b)) }
        lines.append("END:VCARD")
        return lines.map(StructuredText.fold).joined(separator: "\r\n")
    }

    /// A bare VEVENT. Absolute times are UTC, floating times have no `Z`, all-day times are `VALUE=DATE`.
    static func vEvent(_ e: EventInfo) -> String {
        let esc = StructuredText.escapeText
        var lines = ["BEGIN:VEVENT"]
        if let s = StructuredText.nonEmpty(e.summary) { lines.append("SUMMARY:" + esc(s)) }
        let kind: ICSDate.Kind = e.isAllDay ? .allDay : (e.isFloating ? .floating : .utc)
        func stamp(_ name: String, _ d: Date) -> String {
            (kind == .allDay ? name + ";VALUE=DATE:" : name + ":") + ICSDate.format(d, kind: kind)
        }
        if let d = e.start { lines.append(stamp("DTSTART", d)) }
        if let d = e.end { lines.append(stamp("DTEND", d)) }
        if let v = StructuredText.nonEmpty(e.location) { lines.append("LOCATION:" + esc(v)) }
        if let v = StructuredText.nonEmpty(e.description) { lines.append("DESCRIPTION:" + esc(v)) }
        if let v = StructuredText.nonEmpty(e.url) { lines.append("URL:" + esc(v)) }
        lines.append("END:VEVENT")
        return lines.map(StructuredText.fold).joined(separator: "\r\n")
    }
}
