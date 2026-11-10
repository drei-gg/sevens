import Foundation

// Contract: the signature is frozen. The text produced round-trips through the parsers
// in this folder (for the fields each encoding carries).

public enum PayloadEncoder {
    /// The text to put in a generated code. Round-trips through the parsers in this folder.
    public static func encode(_ r: MakeRequest) -> String {
        switch r {
        case .link(let s): return StructuredText.trimmed(s)
        case .text(let s): return s
        case .wifi(let w): return wifi(w)
        case .contact(let c): return PayloadEncoder.vCard(c)
        case .event(let e): return PayloadEncoder.vEvent(e)
        case .email(let e): return email(e)
        case .sms(let s): return sms(s)
        case .phone(let n): return "TEL:" + StructuredText.percentEncode(StructuredText.trimmed(n), keep: numberKeep)
        case .geo(let g): return geo(g)
        }
    }

    /// Characters a phone number keeps readable; everything else is percent-encoded.
    static let numberKeep = "+()*#,;=/ "

    // MARK: Wi-Fi

    private static func wifi(_ w: WiFiInfo) -> String {
        var s = "WIFI:"
        if let sec = StructuredText.nonEmpty(w.security) {
            s += "T:" + (sec.uppercased() == "NOPASS" ? "nopass" : StructuredText.escapeBackslash(sec, ";,:\"")) + ";"
        }
        s += "S:" + wifiValue(w.ssid) + ";"
        if let p = w.password, !p.isEmpty { s += "P:" + wifiValue(p) + ";" }
        if let e = StructuredText.nonEmpty(w.eapMethod) { s += "E:" + StructuredText.escapeBackslash(e, ";,:\"") + ";" }
        if let i = StructuredText.nonEmpty(w.identity) { s += "I:" + StructuredText.escapeBackslash(i, ";,:\"") + ";" }
        if w.isHidden { s += "H:true;" }
        return s + ";"
    }

    /// Escaped; an all-hex value is quoted so scanners don't read it as hex bytes.
    private static func wifiValue(_ v: String) -> String {
        let hex = !v.isEmpty && v.utf8.count % 2 == 0 && v.utf8.allSatisfy { StructuredText.hexValue($0) != nil }
        return hex ? "\"" + v + "\"" : StructuredText.escapeBackslash(v, ";,:\"")
    }

    // MARK: email, sms, geo

    private static func email(_ e: EmailInfo) -> String {
        var s = "mailto:" + StructuredText.percentEncode(StructuredText.trimmed(e.address), keep: "@+")
        var q: [String] = []
        if let v = e.subject, !v.isEmpty { q.append("subject=" + StructuredText.percentEncode(v)) }
        if let v = e.body, !v.isEmpty { q.append("body=" + StructuredText.percentEncode(v)) }
        if !q.isEmpty { s += "?" + q.joined(separator: "&") }
        return s
    }

    private static func sms(_ m: SMSInfo) -> String {
        let number = StructuredText.percentEncode(StructuredText.trimmed(m.number), keep: numberKeep)
        if let b = m.body, !b.isEmpty { return "SMSTO:" + number + ":" + b }
        return "sms:" + number
    }

    private static func geo(_ g: GeoInfo) -> String {
        var s = "geo:" + StructuredText.trimmed(g.latitude) + "," + StructuredText.trimmed(g.longitude)
        if let a = StructuredText.nonEmpty(g.altitude) { s += "," + a }
        if let q = g.query, !q.isEmpty { s += "?q=" + StructuredText.percentEncode(q) }
        return s
    }
}
