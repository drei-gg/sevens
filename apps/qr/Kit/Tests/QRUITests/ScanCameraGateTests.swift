import AVFoundation
import Testing
@testable import QRUI

/// SPEC §1.3: the camera permission is asked when the scanner first opens, never under a launch
/// sheet, and "denied" is told apart from "this device can't scan".
@Suite struct ScanCameraGateTests {
    @Test func permissionStatesMapToAvailability() {
        #expect(ScannerView.availability(supported: true, authorization: .notDetermined, available: false) == .notDetermined)
        #expect(ScannerView.availability(supported: true, authorization: .denied, available: false) == .denied)
        #expect(ScannerView.availability(supported: true, authorization: .restricted, available: false) == .restricted)
        #expect(ScannerView.availability(supported: true, authorization: .authorized, available: true) == .available)
        #expect(ScannerView.availability(supported: true, authorization: .authorized, available: false) == .unsupported)
    }

    @Test func aDeviceThatCantScanIsNeverAskedOrCalledDenied() {
        for status in [AVAuthorizationStatus.notDetermined, .denied, .restricted, .authorized] {
            #expect(ScannerView.availability(supported: false, authorization: status, available: false) == .unsupported)
        }
    }

    @Test func launchSheetsHoldTheCamera() {
        #expect(ScanCameraGate.launchHolds(beforeYouBuyUp: true, trialEndedUp: false, beforeYouBuyDue: false, trialEndedDue: false))
        #expect(ScanCameraGate.launchHolds(beforeYouBuyUp: false, trialEndedUp: false, beforeYouBuyDue: true, trialEndedDue: false))
        #expect(ScanCameraGate.launchHolds(beforeYouBuyUp: false, trialEndedUp: true, beforeYouBuyDue: false, trialEndedDue: false))
        #expect(ScanCameraGate.launchHolds(beforeYouBuyUp: false, trialEndedUp: false, beforeYouBuyDue: false, trialEndedDue: true))
        #expect(!ScanCameraGate.launchHolds(beforeYouBuyUp: false, trialEndedUp: false, beforeYouBuyDue: false, trialEndedDue: false))
    }

    @Test func scannerOpensOnlyWhenNothingCoversIt() {
        #expect(ScanCameraGate.scannerOpen(onScreen: true, sheetUp: false, held: false, sceneActive: true))
        #expect(!ScanCameraGate.scannerOpen(onScreen: true, sheetUp: false, held: true, sceneActive: true))
        #expect(!ScanCameraGate.scannerOpen(onScreen: true, sheetUp: true, held: false, sceneActive: true))
        #expect(!ScanCameraGate.scannerOpen(onScreen: false, sheetUp: false, held: false, sceneActive: true))
        #expect(!ScanCameraGate.scannerOpen(onScreen: true, sheetUp: false, held: false, sceneActive: false))
    }

    @Test func accessIsRequestedOnceWhenTheScannerFirstOpens() {
        #expect(ScanCameraGate.shouldRequestAccess(availability: .notDetermined, scannerOpen: true, requesting: false))
        #expect(!ScanCameraGate.shouldRequestAccess(availability: .notDetermined, scannerOpen: false, requesting: false))
        #expect(!ScanCameraGate.shouldRequestAccess(availability: .notDetermined, scannerOpen: true, requesting: true))
        for other in [ScannerAvailability.available, .denied, .restricted, .unsupported] {
            #expect(!ScanCameraGate.shouldRequestAccess(availability: other, scannerOpen: true, requesting: false))
        }
    }

    @Test func cameraLayoutWhileAskingFallbackOtherwise() {
        #expect(ScanCameraGate.showsCameraLayout(.available))
        #expect(ScanCameraGate.showsCameraLayout(.notDetermined))
        #expect(!ScanCameraGate.showsCameraLayout(.denied))
        #expect(!ScanCameraGate.showsCameraLayout(.unsupported))
        #expect(!ScanCameraGate.showsCameraLayout(.restricted))
    }
}
