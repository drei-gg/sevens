import XCTest

@MainActor final class SmokeTests: XCTestCase {
    func testWaterQuickAddHistorySettingsAndRelaunch() throws {
        let app = XCUIApplication(); app.launchArguments = ["-fast.ui", "-fast.reset"]; app.launch()
        let quick = app.buttons["water.quickAdd"]
        XCTAssertTrue(quick.waitForExistence(timeout: 15))
        func reveal(_ element: XCUIElement, down: Bool = false) {
            for _ in 0..<6 { if element.exists && element.isHittable { return }; if down { app.swipeDown() } else { app.swipeUp() } }
            XCTAssertTrue(element.exists && element.isHittable)
        }
        func total(_ expected: String) {
            let value = app.staticTexts["water.todayTotal"]
            expectation(for: NSPredicate(format: "label == %@", expected), evaluatedWith: value)
            waitForExpectations(timeout: 10)
        }
        reveal(quick); quick.tap(); total("250 mL")
        reveal(app.buttons["water.undo"]); app.buttons["water.undo"].tap(); total("0 mL")
        app.buttons["water.open"].tap()
        app.buttons["water.customAdd"].tap()
        let value = app.textFields["metric.value"]
        XCTAssertTrue(value.waitForExistence(timeout: 5)); value.tap(); value.typeText("500")
        app.buttons["metric.save"].tap()
        let entry = app.buttons.matching(identifier: "water.entry").firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
        value.tap(); value.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 3) + "150")
        app.buttons["metric.save"].tap()
        app.buttons["water.settings"].tap()
        let toggle = app.switches["water.goalEnabled"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5)); toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        let goal = app.textFields["water.goalAmount"]; goal.tap(); goal.typeText("1000")
        let glass = app.textFields["water.glassAmount"]; glass.tap()
        glass.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 3) + "500")
        app.buttons["water.saveSettings"].tap()
        XCTAssertTrue(app.navigationBars["Water"].waitForExistence(timeout: 5))
        app.terminate(); app.launchArguments = ["-fast.ui"]; app.launch()
        XCTAssertTrue(quick.waitForExistence(timeout: 15)); reveal(quick)
        total("150 mL"); XCTAssertTrue(quick.label.contains("500"))
        XCTAssertTrue(app.staticTexts["of 1000 mL"].exists || app.staticTexts["of 1,000 mL"].exists)
        quick.tap(); total("650 mL")
        reveal(app.buttons["water.undo"]); app.buttons["water.undo"].tap(); total("150 mL")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "water-today"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["water.open"].tap()
        reveal(app.buttons["water.trends"]); app.buttons["water.trends"].tap()
        XCTAssertTrue(app.navigationBars["Water trends"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Average on logged days")).firstMatch.exists)
        let chart = XCTAttachment(screenshot: app.screenshot()); chart.name = "water-trends"; chart.lifetime = .keepAlways; add(chart)
        app.navigationBars["Water trends"].buttons.firstMatch.tap()
        reveal(entry, down: true); entry.swipeLeft(); app.buttons["Delete"].firstMatch.tap()
        app.buttons["Delete drink"].tap()
        XCTAssertTrue(app.staticTexts["No water logged for this day."].waitForExistence(timeout: 5))
    }
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
