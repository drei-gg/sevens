import Foundation
import QRCore
import SevenKit

/// SPEC §4.1 verdict words, the matching icon, payload type names, and the VoiceOver sentence.
/// Level 0 reads "Looks normal", never a reassurance (G7).
public enum VerdictText {
    public static func title(for level: Level) -> String {
        switch level {
        case .normal: return String(localized: "verdict.normal", defaultValue: "Looks normal", bundle: .module)
        case .careful: return String(localized: "verdict.careful", defaultValue: "Be careful", bundle: .module)
        case .likelyScam: return String(localized: "verdict.likelyScam", defaultValue: "Likely scam", bundle: .module)
        }
    }

    /// Color and SF Symbol come from SevenKit.
    public static func style(for level: Level) -> Tokens.VerdictStyle {
        switch level {
        case .normal: return .normal
        case .careful: return .careful
        case .likelyScam: return .likelyScam
        }
    }

    public static func symbolName(for level: Level) -> String { style(for: level).symbolName }

    /// Display name of a `Payload.typeID` (also what History search matches). Unknown IDs, such
    /// as ones written by a newer version, read "Other".
    public static func typeName(for typeID: String) -> String {
        switch typeID {
        case "link": return String(localized: "type.link", defaultValue: "Web link", bundle: .module)
        case "appLink": return String(localized: "type.appLink", defaultValue: "App link", bundle: .module)
        case "payment": return String(localized: "type.payment", defaultValue: "Payment code", bundle: .module)
        case "crypto": return String(localized: "type.crypto", defaultValue: "Crypto address", bundle: .module)
        case "wifi": return String(localized: "type.wifi", defaultValue: "Wi-Fi network", bundle: .module)
        case "contact": return String(localized: "type.contact", defaultValue: "Contact", bundle: .module)
        case "event": return String(localized: "type.event", defaultValue: "Event", bundle: .module)
        case "phone": return String(localized: "type.phone", defaultValue: "Phone number", bundle: .module)
        case "sms": return String(localized: "type.sms", defaultValue: "Text message", bundle: .module)
        case "email": return String(localized: "type.email", defaultValue: "Email", bundle: .module)
        case "geo": return String(localized: "type.geo", defaultValue: "Location", bundle: .module)
        case "text": return String(localized: "type.text", defaultValue: "Text", bundle: .module)
        case "binary": return String(localized: "type.binary", defaultValue: "Other data", bundle: .module)
        default: return String(localized: "type.other", defaultValue: "Other", bundle: .module)
        }
    }

    /// The verdict read aloud as one sentence: "Be careful. Web link. This link isn't encrypted… "
    /// Info signals are left out (they never change the verdict); pass only what you want read.
    public static func accessibilitySentence(level: Level, typeName: String, signals: [Signal]) -> String {
        let reasons = SignalText.sentences(for: signals.filter { $0.severity != .info })
        var parts = [title(for: level), typeName]
        parts.append(contentsOf: reasons)
        return parts.map { $0.trimmingCharacters(in: CharacterSet(charactersIn: ". ")) }
            .filter { !$0.isEmpty }
            .map { $0 + "." }
            .joined(separator: " ")
    }
}
