import XCTest

@MainActor final class SmokeTests: XCTestCase {
    func testStartRelaunchEndAndHistory() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fast.ui", "-fast.reset"]
        app.launch()
        XCTAssertTrue(app.buttons["fast.start"].waitForExistence(timeout: 15))
        app.buttons["fast.start"].tap()
        XCTAssertTrue(app.buttons["fast.save"].waitForExistence(timeout: 5))
        app.buttons["fast.save"].tap()
        XCTAssertTrue(app.buttons["fast.end"].waitForExistence(timeout: 5))
        app.terminate()
        app.launchArguments = ["-fast.ui"]
        app.launch()
        XCTAssertTrue(app.buttons["fast.end"].waitForExistence(timeout: 15))
        app.buttons["fast.end"].tap()
        app.buttons["fast.save"].tap()
        XCTAssertTrue(app.buttons["fast.start"].waitForExistence(timeout: 5))
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.navigationBars["History"].waitForExistence(timeout: 5))
        app.segmentedControls.buttons["List"].tap()
        XCTAssertFalse(app.staticTexts["No matching fasts"].exists)
    }
    func testJournalAndCalendarViews() throws {
        let app = XCUIApplication(); app.launchArguments = ["-fast.demo"]; app.launch()
        XCTAssertTrue(app.tabBars.buttons["Journal"].waitForExistence(timeout: 15))
        app.tabBars.buttons["Journal"].tap()
        app.buttons["New journal entry"].tap()
        let field = app.descendants(matching: .any).matching(identifier: "journal.text").firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("A useful observation from the simulator.")
        app.buttons["journal.save"].tap()
        XCTAssertTrue(app.staticTexts["A useful observation from the simulator."].waitForExistence(timeout: 5))
        app.tabBars.buttons["History"].tap()
        app.segmentedControls.buttons["Heatmap"].tap()
        XCTAssertTrue(app.staticTexts["Darker means more recorded time, not a better day. A dot marks a journal entry."].exists)
        app.segmentedControls.buttons["List"].tap()
        app.tabBars.buttons["Trends"].tap()
        XCTAssertTrue(app.staticTexts["Recorded hours"].exists)
    }
}
