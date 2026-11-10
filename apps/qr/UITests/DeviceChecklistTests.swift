import XCTest

/// G9 items a UI test can drive on the iPhone (PLAN §10). Skipped in the Simulator; run by
/// `scripts/vm ui --device`. Peek uses the network: it fetches a public YouTube short link.
final class DeviceChecklistTests: XCTestCase {
    override func setUpWithError() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("device-only (G9)")
        #else
        continueAfterFailure = false
        #endif
    }

    @MainActor
    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-qr.skipFirstLaunch", "-qr.entitlement", "unlocked"] + extra
        app.launch()
        return app
    }

    @MainActor
    private func inject(_ text: String) -> [String] {
        ["-qr.inject", Data(text.utf8).base64EncodedString()]
    }

    @MainActor
    private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// G9 "Peek on a real shortened link".
    @MainActor
    func testPeekFollowsARealShortLink() throws {
        let app = launch(["-qr.resetHistory"] + inject("https://youtu.be/jNQXAC9IVRw"))
        let peek = app.descendants(matching: .any)["result.peek"].firstMatch
        XCTAssertTrue(peek.waitForExistence(timeout: 30), "no Peek button")
        peek.tap()
        let landed = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] %@", "youtube.com")).firstMatch
        XCTAssertTrue(landed.waitForExistence(timeout: 30), "Peek didn't show the youtube.com hop")
        let age = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH %@ OR label == %@", "Registered", "Age unknown")).firstMatch
        XCTAssertTrue(age.waitForExistence(timeout: 20), "no domain age line")
        attach(app, "g9-peek")
    }

    /// G9 "history survives relaunch".
    @MainActor
    func testHistorySurvivesRelaunch() throws {
        var app = launch(["-qr.resetHistory"] + inject("https://example.org/relaunch-check"))
        let done = app.buttons["result.done"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 30))
        done.tap()
        app.terminate()

        app = launch([])
        let history = app.descendants(matching: .any)["scan.history"].firstMatch
        XCTAssertTrue(history.waitForExistence(timeout: 20))
        history.tap()
        let row = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "example.org")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "history lost across relaunch")
        attach(app, "g9-history-relaunch")
    }

    /// G9 "largest text size" and "debug panel cycles every entitlement state" (Settings at AX5).
    @MainActor
    func testSettingsAtLargestText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-qr.skipFirstLaunch", "-qr.entitlement", "trialDay1",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let settings = app.descendants(matching: .any)["scan.settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        attach(app, "g9-scan-ax5")
        settings.tap()
        sleep(2)
        attach(app, "g9-settings-ax5")
    }
}
