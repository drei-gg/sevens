import XCTest
@MainActor final class SmokeTests: XCTestCase {
    private func launch() -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.seed"]; app.launch(); return app
    }
    func testAllCleanupAreasAreReachableWithoutPermissionOnLaunch() {
        let app = launch()
        XCTAssertTrue(app.navigationBars["Cleaner"].waitForExistence(timeout: 30))
        for title in ["Photos", "Videos", "Files", "Contacts"] {
            app.tabBars.buttons[title].tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
        }
        XCTAssertTrue(app.buttons["Scan contacts"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "cleaner-contacts"; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testExactSuggestionKeepsFavoriteAndPersistsReview() {
        let app = launch()
        XCTAssertTrue(app.navigationBars["Cleaner"].waitForExistence(timeout: 30))
        app.buttons["cleaner.groups"].tap()
        app.buttons["Select suggested exact copies"].tap()
        app.buttons["cleaner.queue-selection"].tap()
        app.buttons["Review pending removal"].tap()
        XCTAssertTrue(app.staticTexts["Example copy"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Example landscape"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "cleaner-pending-review"; attachment.lifetime = .keepAlways; add(attachment)
        app.terminate(); app.launchArguments = ["-cleaner.ui", "-cleaner.seed"]; app.launch()
        XCTAssertTrue(app.buttons["cleaner.pending"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.buttons["cleaner.pending"].value as? String,"1")
    }
}
