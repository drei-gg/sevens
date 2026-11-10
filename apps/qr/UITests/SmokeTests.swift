import XCTest

/// PLAN §8 WP 4.1: the one UI smoke test.
/// Inject a userinfo-trick link → "Likely scam" is visible → dismiss → Make a link code → History shows it.
/// Debug-only launch arguments (QRUI `QRLaunchArgument`) set up a known state; identifiers are
/// QRUI's `QRAccessibilityID` values, repeated here as literals because UI tests can't import QRUI.
final class SmokeTests: XCTestCase {
    private let trick = "https://bank.com@evil.site/login"
    private let made = "https://example.com/smoke-test"

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testScanVerdictMakeAndHistory() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-qr.skipFirstLaunch",
            "-qr.entitlement", "unlocked",
            "-qr.resetHistory",
            "-qr.inject", Data(trick.utf8).base64EncodedString(),
        ]
        app.launch()

        // 1. The injected code opens the result sheet with the verdict.
        let verdict = app.descendants(matching: .any)["result.verdict"].firstMatch
        XCTAssertTrue(verdict.waitForExistence(timeout: 30), "result sheet did not appear")
        XCTAssertTrue(verdict.label.contains("Likely scam"), "verdict label was: \(verdict.label)")
        attach(app, "1-result")

        // 2. Dismiss it.
        app.buttons["result.done"].firstMatch.tap()
        XCTAssertTrue(verdict.waitForNonExistence(timeout: 10), "result sheet did not dismiss")

        // 3. Make a link code.
        let makeButton = app.descendants(matching: .any)["scan.make"].firstMatch
        XCTAssertTrue(makeButton.waitForExistence(timeout: 10))
        makeButton.tap()
        let field = app.textFields["make.field.link"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Make link field missing")
        field.tap()
        field.typeText(made)
        let code = app.descendants(matching: .any)["make.code"].firstMatch
        XCTAssertTrue(code.waitForExistence(timeout: 10), "no generated code shown")
        attach(app, "2-make")
        app.buttons["make.done"].firstMatch.tap()

        // 4. History shows the made code (and the scanned one).
        let historyButton = app.descendants(matching: .any)["scan.history"].firstMatch
        XCTAssertTrue(historyButton.waitForExistence(timeout: 10))
        historyButton.tap()
        let row = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "example.com")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "made code not in History")
        attach(app, "3-history")
    }

    @MainActor
    private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
