import Foundation

/// tel:
public enum PhoneParser {
    public static func parse(_ text: String) -> PhoneInfo? {
        guard let rest = StructuredText.stripPrefix(StructuredText.prepared(text), "tel:") else { return nil }
        guard let number = StructuredText.nonEmpty(StructuredText.percentDecode(rest)) else { return nil }
        return PhoneInfo(number: number)
    }
}
