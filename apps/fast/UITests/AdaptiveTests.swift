import XCTest

/// Resizes the same app/window and open editor. Actual Duo hinge/runtime acceptance is separate.
@MainActor final class AdaptiveTests: XCTestCase {
    func testLandscapeHistoryAndDraftSurviveResize() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fast.demo"]
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["History"].waitForExistence(timeout: 15))
        app.tabBars.buttons["History"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.buttons["Jump to date"].waitForExistence(timeout: 5))
        app.segmentedControls.buttons["Heatmap"].tap()
        app.segmentedControls.buttons["Calendar"].tap()
        let calendar = app.otherElements["history.calendarPanel"]
        let day = app.otherElements["history.dayPanel"]
        XCTAssertTrue(calendar.waitForExistence(timeout: 5))
        XCTAssertTrue(day.waitForExistence(timeout: 5))
        XCTAssertLessThanOrEqual(calendar.frame.maxX, day.frame.minX)
        XCTAssertEqual(calendar.frame.minY, day.frame.minY, accuracy: 2)
        let history = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); history.name = "history-wide"; history.lifetime = .keepAlways; add(history)
        XCUIDevice.shared.orientation = .portrait
        app.tabBars.buttons["Journal"].tap()
        app.buttons["New journal entry"].tap()
        let field = app.descendants(matching: .any).matching(identifier: "journal.text").firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("Keep this draft while the window changes.")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(String(describing: field.value).contains("Keep this draft"))
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(String(describing: field.value).contains("Keep this draft"))
        app.buttons["journal.save"].tap()
        XCTAssertTrue(app.staticTexts["Keep this draft while the window changes."].waitForExistence(timeout: 5))
    }
}
