import Foundation
import PhoneNumberKit

/// Confine one parser to its scan's actor; loading libphonenumber metadata for each field is expensive.
public final class PhoneNormalizer {
    private let utility = PhoneNumberUtility()
    public init() {}
    public func normalized(_ raw: String, region: String?) -> String? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.hasPrefix("+") || region != nil else { return nil }
        // PhoneNumberKit requires real region metadata even for explicit international prefixes.
        // With '+', the calling code wins; US is only the parser's initial territory, not an inference.
        guard let number = try? utility.parse(text, withRegion: region ?? "US") else { return nil }
        return utility.format(number, toType: .e164) + (number.numberExtension.map { ";ext=" + $0 } ?? "")
    }
}
public enum PhoneKeys {
    public static func normalized(_ raw: String, region: String?) -> String? { PhoneNormalizer().normalized(raw, region: region) }
}
