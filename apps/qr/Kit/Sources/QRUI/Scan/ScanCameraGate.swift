import Foundation

/// When the live camera may run, and when to ask for it (SPEC §1.3: the permission is asked
/// when the scanner first opens, never over Before you buy or another sheet). Pure, so the
/// rules are unit-tested; ScanScreen and RootView feed it.
enum ScanCameraGate {
    /// A launch sheet holds the camera off while it is up, and while it is due but not yet up
    /// (so the permission prompt can't appear in the moment before Before you buy slides in).
    static func launchHolds(beforeYouBuyUp: Bool, trialEndedUp: Bool, beforeYouBuyDue: Bool, trialEndedDue: Bool) -> Bool {
        beforeYouBuyUp || trialEndedUp || beforeYouBuyDue || trialEndedDue
    }

    /// The scanner is open: the scan screen is the one showing (nothing pushed over it, so the
    /// unlock sheet over a gated Make counts too), no result, pick list, Before you buy or
    /// trial-ended sheet covers it, and the app is in front.
    static func scannerOpen(onScreen: Bool, sheetUp: Bool, held: Bool, sceneActive: Bool) -> Bool {
        onScreen && !sheetUp && !held && sceneActive
    }

    /// Ask once, the first time the scanner opens while the person hasn't been asked.
    static func shouldRequestAccess(availability: ScannerAvailability, scannerOpen: Bool, requesting: Bool) -> Bool {
        availability == .notDetermined && scannerOpen && !requesting
    }

    /// The camera layout (frame, torch) shows for a usable scanner and while the permission is
    /// still to be asked; every other case shows the photo and paste fallback with its reason.
    static func showsCameraLayout(_ availability: ScannerAvailability) -> Bool {
        availability == .available || availability == .notDetermined
    }
}
