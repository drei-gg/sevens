import Foundation
import QRCore

/// Every action a result can offer (PLAN §6.7, SPEC §3).
enum ResultAction: Hashable, Sendable {
    case open, peek
    case joinWiFi, copyPassword
    case addContact, addEvent
    case call, message, mail, maps
    case openPaymentIn
    case copy, copyDetails, share
    case makeCode
}

/// What this device and build can do. Read once per sheet; tests pass their own.
struct ResultCapabilities: Equatable, Sendable {
    /// Info.plist `SevensWiFiJoinAvailable` == "YES" (C8: off when the Hotspot entitlement is missing).
    var wifiJoinAvailable: Bool
    /// Calendar access isn't restricted (C14).
    var canAddEvent: Bool
    /// `MFMessageComposeViewController.canSendText()`; when false, Message falls back to `sms:`.
    var canComposeMessage: Bool

    init(wifiJoinAvailable: Bool = false, canAddEvent: Bool = true, canComposeMessage: Bool = false) {
        self.wifiJoinAvailable = wifiJoinAvailable
        self.canAddEvent = canAddEvent
        self.canComposeMessage = canComposeMessage
    }

    static func wifiFlag(in bundle: Bundle) -> Bool {
        (bundle.object(forInfoDictionaryKey: "SevensWiFiJoinAvailable") as? String)?
            .trimmingCharacters(in: .whitespaces).uppercased() == "YES"
    }
}

/// How the Wi-Fi card behaves (C8, enterprise rule).
enum WiFiJoinPlan: Equatable, Sendable {
    /// A Join button.
    case join
    /// Enterprise network: "Join this one in Settings".
    case settingsOnly
    /// Join isn't available in this build: "Copy password" and "Join in Settings › Wi-Fi".
    case copyAndSettings
    /// A secured network whose code carries no password: nothing to join with.
    case cannotJoin
}

/// Pure decisions about the result sheet's buttons.
enum ResultActionPlan {
    /// The buttons for a payload, in display order.
    static func actions(for payload: Payload, capabilities: ResultCapabilities, hasText: Bool = true) -> [ResultAction] {
        var list: [ResultAction] = []
        switch payload {
        case .link(let link):
            if canOpen(link) { list += [.peek, .open] }
            list += [.copy, .share]
        case .appLink:
            list += [.copy, .share]
        case .payment:
            if isURIShapedPayment(payload) { list.append(.openPaymentIn) }
            list += [.copyDetails]
        case .crypto(let info):
            if info.scheme != nil { list.append(.openPaymentIn) }
            list += [.copy]
        case .wifi(let info):
            if wifiJoinPlan(info, capabilities: capabilities) == .join { list.append(.joinWiFi) }
            if hasPassword(info) { list.append(.copyPassword) }
        case .contact:
            list += [.addContact, .copy, .share]
        case .event:
            if capabilities.canAddEvent { list.append(.addEvent) }
            list += [.copy, .share]
        case .phone:
            list += [.call, .copy]
        case .sms(let info):
            if !info.number.isEmpty { list.append(.message) }
            list += [.copy, .share]
        case .email:
            list += [.mail, .copy, .share]
        case .geo:
            list += [.maps, .copy]
        case .text:
            list += [.copy, .share]
        case .binary:
            break
        }
        if hasText, ResultMakeMapping.supportsMake(payload) { list.append(.makeCode) }
        return list
    }

    static func wifiJoinPlan(_ info: WiFiInfo, capabilities: ResultCapabilities) -> WiFiJoinPlan {
        if info.isEnterprise { return .settingsOnly }
        guard capabilities.wifiJoinAvailable else { return .copyAndSettings }
        if info.isOpen || hasPassword(info) { return .join }
        return .cannotJoin
    }

    static func hasPassword(_ info: WiFiInfo) -> Bool { !(info.password ?? "").isEmpty }

    /// D8 and PLAN §6.7: a `likelyScam` result asks first, naming the registrable domain.
    static func needsOpenConfirmation(level: Level) -> Bool { level == .likelyScam }

    /// Only http(s) links with a host can be opened or Peeked (SFSafariViewController refuses anything else).
    static func canOpen(_ link: LinkInfo) -> Bool {
        guard !link.hostASCII.isEmpty else { return false }
        let scheme = link.scheme.lowercased()
        return scheme == "http" || scheme == "https"
    }

    /// UPI and crypto URIs go to the system; EMVCo, EPC and Swiss QR-bill have no URI.
    static func isURIShapedPayment(_ payload: Payload) -> Bool {
        switch payload {
        case .payment(let info): return info.format == .upi
        case .crypto(let info): return info.scheme != nil
        default: return false
        }
    }
}
