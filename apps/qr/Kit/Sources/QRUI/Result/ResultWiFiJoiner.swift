import Foundation
import NetworkExtension
import QRCore

/// `NEHotspotConfigurationManager.apply` (joinOnce = false). Only reached when the build has the
/// Hotspot entitlement (C8 flag) and the network isn't enterprise.
enum ResultWiFiJoiner {
    enum Outcome: Equatable { case joined, alreadyJoined, declined, failed }

    static func configuration(for info: WiFiInfo) -> NEHotspotConfiguration? {
        guard !info.ssid.isEmpty, info.ssid.utf8.count <= 32, !info.isEnterprise else { return nil }
        let config: NEHotspotConfiguration
        if info.isOpen {
            config = NEHotspotConfiguration(ssid: info.ssid)
        } else if let password = info.password, !password.isEmpty {
            config = NEHotspotConfiguration(ssid: info.ssid, passphrase: password,
                                            isWEP: info.security?.uppercased() == "WEP")
        } else {
            return nil
        }
        config.joinOnce = false
        config.hidden = info.isHidden
        return config
    }

    @MainActor
    static func join(_ info: WiFiInfo) async -> Outcome {
        guard let config = configuration(for: info) else { return .failed }
        let error: NSError? = await withCheckedContinuation { continuation in
            NEHotspotConfigurationManager.shared.apply(config) { error in
                continuation.resume(returning: error.map { $0 as NSError })
            }
        }
        guard let error else { return .joined }
        guard error.domain == NEHotspotConfigurationErrorDomain else { return .failed }
        switch NEHotspotConfigurationError(rawValue: error.code) {
        case .alreadyAssociated: return .alreadyJoined
        case .userDenied: return .declined
        default: return .failed
        }
    }
}
