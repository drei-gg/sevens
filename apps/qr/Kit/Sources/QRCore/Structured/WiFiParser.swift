import Foundation

/// `WIFI:T:WPA;S:name;P:pass;H:true;;`
public enum WiFiParser {
    public static func parse(_ text: String) -> WiFiInfo? {
        guard let body = StructuredText.stripPrefix(StructuredText.prepared(text), "WIFI:") else { return nil }
        var ssid: String?, security: String?, password: String?, eap: String?, identity: String?
        var hidden = false
        for (key, raw) in StructuredText.keyed(body) {
            let value = literal(raw)
            switch key {
            case "S": if ssid == nil { ssid = value }
            case "T": if security == nil { security = StructuredText.nonEmpty(value)?.uppercased() }
            case "P": if password == nil, !value.isEmpty { password = value }
            case "H": hidden = hidden || value.trimmingCharacters(in: .whitespaces).lowercased() == "true"
            case "E": if eap == nil { eap = StructuredText.nonEmpty(value) }
            case "I": if identity == nil { identity = StructuredText.nonEmpty(value) }
            default: break
            }
        }
        guard let ssid, !ssid.isEmpty else { return nil }
        return WiFiInfo(ssid: ssid, security: security, password: password, isHidden: hidden,
                        eapMethod: eap, identity: identity)
    }

    /// A value wrapped in double quotes is literal text (no hex decoding); otherwise unescape.
    private static func literal(_ raw: String) -> String {
        let sc = Array(raw.unicodeScalars)
        if sc.count >= 2, sc.first == "\"", sc.last == "\"" {
            return StructuredText.unescapeBackslash(StructuredText.make(Array(sc[1..<(sc.count - 1)])))
        }
        return StructuredText.unescapeBackslash(raw)
    }
}
