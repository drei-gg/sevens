import XCTest

/// Not part of the smoke run. Brings another app to the front on the connected iPhone and
/// screenshots it, for design comparisons against Apple's own apps. Set SEVENS_SHOT_BUNDLE
/// (default: iMovie). `activate()` resumes the app where it was; it doesn't relaunch it.
final class DeviceShotTests: XCTestCase {
    @MainActor
    func testShotOfOtherApp() throws {
        #if targetEnvironment(simulator)
        try XCTSkipIf(true, "Device only: the simulator has none of Apple's apps to compare against.")
        #endif
        let id = ProcessInfo.processInfo.environment["SEVENS_SHOT_BUNDLE"] ?? "com.apple.iMovie"
        let other = XCUIApplication(bundleIdentifier: id)
        other.activate()
        sleep(2)
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = "device-\(id)"; a.lifetime = .keepAlways; add(a)
    }
}
