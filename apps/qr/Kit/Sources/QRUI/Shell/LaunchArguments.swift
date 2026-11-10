import Foundation

/// Debug launch arguments (QRLaunchArgument), parsed from `ProcessInfo.arguments`. Pure, so it
/// can be tested; `AppModel.runLaunch()` applies it in Debug builds only (Release ignores all).
struct LaunchArguments: Equatable, Sendable {
    /// Most bytes `-qr.inject` will decode; the analyzer caps its own input far below this.
    static let maxInjectBytes = 64 * 1024
    /// Preset names `-qr.entitlement` accepts (SevenKit's `LocalSource.Preset` raw values).
    static let entitlementNames = ["notStarted", "trialDay1", "trialDay7", "expired", "unlocked", "pending"]

    var inject: [UInt8]?
    var skipFirstLaunch = false
    var entitlement: String?
    var resetHistory = false
    var resetKnown = false
    var resetFlags = false

    static func parse(_ arguments: [String]) -> LaunchArguments {
        var result = LaunchArguments()
        var index = 0
        while index < arguments.count {
            let argument = arguments[index]
            let value = index + 1 < arguments.count ? arguments[index + 1] : nil
            switch argument {
            case QRLaunchArgument.inject:
                if let value, let bytes = decodeBase64(value) { result.inject = bytes; index += 1 }
            case QRLaunchArgument.skipFirstLaunch:
                result.skipFirstLaunch = true
            case QRLaunchArgument.entitlement:
                if let value, entitlementNames.contains(value) { result.entitlement = value; index += 1 }
            case QRLaunchArgument.resetHistory:
                result.resetHistory = true
            case QRLaunchArgument.resetKnown:
                result.resetKnown = true
            case QRLaunchArgument.resetFlags:
                result.resetFlags = true
            default:
                break
            }
            index += 1
        }
        return result
    }

    /// Standard or URL-safe Base64, padding optional. Nil when empty, invalid or too long.
    static func decodeBase64(_ string: String) -> [UInt8]? {
        var s = string.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        guard !s.isEmpty, s.utf8.count <= maxInjectBytes * 2 else { return nil }
        while s.utf8.count % 4 != 0 { s += "=" }
        guard let data = Data(base64Encoded: s), !data.isEmpty, data.count <= maxInjectBytes else { return nil }
        return [UInt8](data)
    }
}
