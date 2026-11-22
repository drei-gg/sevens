import XCTest

/// The one UI smoke test.
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
    func testBatchReviewExportsAndKeepsInputs() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-qr.skipFirstLaunch", "-qr.entitlement", "unlocked", "-qr.resetHistory"]
        app.launch()
        let make = app.descendants(matching: .any)["scan.make"].firstMatch
        XCTAssertTrue(make.waitForExistence(timeout: 20)); make.tap()
        let batch = app.buttons["Batch codes"]
        if !batch.isHittable { app.buttons["More"].tap() }
        XCTAssertTrue(batch.waitForExistence(timeout: 5)); batch.tap()
        let input = app.textViews["batch-input"]
        XCTAssertTrue(input.waitForExistence(timeout: 5)); input.tap()
        let payload = "https://example.com/?utm_source=labels\nDuplicate\nDuplicate\nFour\nFive\nSix\nSeven"
        input.typeText(payload)
        app.buttons["batch-review-export"].tap()
        XCTAssertTrue(app.staticTexts["https://example.com/?utm_source=labels"].waitForExistence(timeout: 5))
        attach(app, "batch-review")
        app.swipeUp(); app.swipeUp()
        app.buttons["batch-review-export"].tap()
        let share = app.buttons["batch-share"]
        XCTAssertTrue(share.waitForExistence(timeout: 20)); XCTAssertTrue(share.isHittable)
        attach(app, "batch-ready")
        share.tap()
        let close = app.buttons["Close"]
        XCTAssertTrue(close.waitForExistence(timeout: 10)); close.tap()
        XCTAssertTrue(share.waitForExistence(timeout: 10))
        for _ in 0..<8 { if app.buttons["Edit inputs"].isHittable { break }; app.swipeDown() }
        app.buttons["Edit inputs"].tap()
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        XCTAssertEqual(input.value as? String, payload)
        app.buttons["Close"].tap()
        XCTAssertTrue(app.buttons["Discard"].waitForExistence(timeout: 5)); app.buttons["Discard"].tap()
        XCTAssertTrue(app.textFields["make.field.link"].waitForExistence(timeout: 5))
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

    /// SPEC §4.1: a text whose verdict comes from a link inside it says so at the top, naming the link.
    @MainActor
    func testInnerLinkReasonShowsUnderTheVerdict() throws {
        let app = launchInjecting("Pay your parking fine here \(trick)")
        let verdict = app.descendants(matching: .any)["result.verdict"].firstMatch
        XCTAssertTrue(verdict.waitForExistence(timeout: 30), "result sheet did not appear")
        XCTAssertTrue(verdict.label.contains("Likely scam"), "verdict label was: \(verdict.label)")
        XCTAssertTrue(labelled(app, "Link to ").waitForExistence(timeout: 5), "no reason names the link inside")
        attach(app, "inner-link-reason")
    }

    /// SPEC §5: a payment code's fields are human-labelled rows, with the spec's fallback line.
    @MainActor
    func testPaymentShowsHumanLabelledRows() throws {
        let app = launchInjecting("upi://pay?pa=asha@okbank&pn=Asha%20Rao&am=50&cu=INR&tn=Tea")
        let verdict = app.descendants(matching: .any)["result.verdict"].firstMatch
        XCTAssertTrue(verdict.waitForExistence(timeout: 30), "result sheet did not appear")
        XCTAssertTrue(labelled(app, "Check this matches where you are.").waitForExistence(timeout: 5))
        XCTAssertTrue(labelled(app, "Payee address").exists, "no payee address row")
        XCTAssertTrue(labelled(app, "asha@okbank").exists)
        XCTAssertTrue(labelled(app, "Note").exists, "no note row")
        attach(app, "payment-rows")
    }

    @MainActor
    private func launchInjecting(_ text: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-qr.skipFirstLaunch", "-qr.entitlement", "unlocked", "-qr.resetHistory",
                               "-qr.inject", Data(text.utf8).base64EncodedString()]
        app.launch()
        return app
    }

    @MainActor
    private func labelled(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    @MainActor
    private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
