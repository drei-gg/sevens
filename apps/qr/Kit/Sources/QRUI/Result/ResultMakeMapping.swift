import Foundation
import QRCore

/// Payload → what Make should be pre-filled with (PLAN §6.7 "Make code").
enum ResultMakeMapping {
    /// Link, text, Wi-Fi, contact, event, email, SMS, phone and location map to their own Make
    /// type; payments, crypto and app links become text of the original. Binary has no text: nil.
    static func request(for analysis: Analysis) -> MakeRequest? {
        switch analysis.payload {
        case .link(let info): return .link(info.original)
        case .text(let info): return .text(info.text)
        case .wifi(let info): return .wifi(info)
        case .contact(let info): return .contact(info)
        case .event(let info): return .event(info)
        case .email(let info): return .email(info)
        case .sms(let info): return .sms(info)
        case .phone(let info): return .phone(info.number)
        case .geo(let info): return .geo(info)
        case .appLink, .payment, .crypto:
            guard let text = analysis.code.text, !text.isEmpty else { return nil }
            return .text(text)
        case .binary: return nil
        }
    }

    static func supportsMake(_ payload: Payload) -> Bool {
        if case .binary = payload { return false }
        return true
    }
}
