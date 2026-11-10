import Foundation
import QRCore
import SevenKit

extension AppModel {
    /// The app's whole launch sequence: Debug launch arguments (Debug builds only), `start()`,
    /// then the arguments that need a started model. Release builds just call `start()`.
    public func runLaunch() async {
        #if DEBUG
        let arguments = LaunchArguments.parse(ProcessInfo.processInfo.arguments)
        if arguments.resetFlags { SevenFlags.resetShownFlags() }
        if arguments.skipFirstLaunch { store.markBeforeYouBuyShown() }
        await start()
        if arguments.resetHistory, !historyIsReadOnly { await clearHistory() }
        if arguments.resetKnown { _ = await forgetAllAddresses() }
        if let name = arguments.entitlement, let preset = LocalSource.Preset(rawValue: name) {
            store.applyDebugPreset(preset)
        }
        if let bytes = arguments.inject {
            // Last, so the scan screen records it after the reset and the preset.
            pendingCode = ScannedCode(text: String(bytes: bytes, encoding: .utf8), bytes: bytes, symbology: .qr)
        }
        #else
        await start()
        #endif
    }
}
