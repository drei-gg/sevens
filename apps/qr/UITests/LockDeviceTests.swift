import XCTest

/// Not part of the smoke run. `scripts/vm lock-device` runs only this, as the very last device
/// step, so the iPhone isn't left unlocked (Drei's request). `pressLockButton` is private
/// test-runner API: it lives in this UI-test target only, never in the app or the kits.
final class LockDeviceTests: XCTestCase {
    @MainActor
    func testZZLockDevice() throws {
        let selector = NSSelectorFromString("pressLockButton")
        guard XCUIDevice.shared.responds(to: selector) else {
            throw XCTSkip("XCUIDevice has no pressLockButton in this Xcode")
        }
        _ = XCUIDevice.shared.perform(selector)
    }
}
