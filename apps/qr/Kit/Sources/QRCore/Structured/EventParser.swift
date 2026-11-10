import Foundation

/// BEGIN:VEVENT, or a VCALENDAR containing one (the first VEVENT).
public enum EventParser {
    public static func parse(_ text: String) -> EventInfo? {
        let t = StructuredText.prepared(text)
        guard StructuredText.stripPrefix(t, "BEGIN:VEVENT") != nil
                || StructuredText.stripPrefix(t, "BEGIN:VCALENDAR") != nil else { return nil }
        var inEvent = false
        var depth = 0
        var info = EventInfo()
        var startLine: ContentLine?, endLine: ContentLine?, durationValue: String?
        for raw in ContentLines.logical(t) {
            guard let line = ContentLines.parse(raw) else { continue }
            let marker = line.rawValue.trimmingCharacters(in: .whitespaces).uppercased()
            if !inEvent {
                if line.name == "BEGIN", marker == "VEVENT" { inEvent = true }
                continue
            }
            if line.name == "BEGIN" { depth += 1; continue }
            if line.name == "END" {
                if depth == 0 { break }
                depth -= 1
                continue
            }
            if depth > 0 { continue }
            switch line.name {
            case "SUMMARY": if info.summary == nil { info.summary = StructuredText.nonEmpty(line.text) }
            case "LOCATION": if info.location == nil { info.location = StructuredText.nonEmpty(line.text) }
            case "DESCRIPTION": if info.description == nil { info.description = StructuredText.nonEmpty(line.text) }
            case "URL": if info.url == nil { info.url = StructuredText.nonEmpty(line.text) }
            case "DTSTART": if startLine == nil { startLine = line }
            case "DTEND": if endLine == nil { endLine = line }
            case "DURATION": if durationValue == nil { durationValue = line.rawValue }
            default: break
            }
        }
        guard inEvent else { return nil }
        if let l = startLine, let s = ICSDate.parse(l.rawValue, tzid: l.param("TZID")) {
            info.start = s.date
            switch s.kind {
            case .allDay: info.isAllDay = true
            case .floating: info.isFloating = true
            case .zoned(let id): info.timeZoneID = id
            case .utc: break
            }
        }
        if let l = endLine, let e = ICSDate.parse(l.rawValue, tzid: l.param("TZID")) {
            info.end = e.date
        } else if let start = info.start, let d = durationValue, let secs = ICSDate.duration(d) {
            info.end = start.addingTimeInterval(secs)
        }
        if let u = info.url { info.links = [u] }
        return info
    }
}
