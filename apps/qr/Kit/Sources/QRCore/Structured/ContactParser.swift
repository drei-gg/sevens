import Foundation

/// vCard 2.1/3.0/4.0 and MeCard.
public enum ContactParser {
    public static func parse(_ text: String) -> ContactInfo? {
        let t = StructuredText.prepared(text)
        if let body = StructuredText.stripPrefix(t, "MECARD:") { return MeCardParser.parse(body: body) }
        if StructuredText.stripPrefix(t, "BEGIN:VCARD") != nil { return VCardParser.parse(t) }
        return nil
    }
}
