import XCTest

@MainActor final class ScreensTourTests: XCTestCase {
    func testLightAndDarkScreens() throws {
        for style in ["Light", "Dark"] {
            let app = XCUIApplication(); app.launchArguments = ["-fast.demo", style == "Dark" ? "-fast.dark" : "-fast.light"]; app.launch()
            XCTAssertTrue(app.tabBars.buttons["Today"].waitForExistence(timeout: 15))
            capture(app, "\(style)-today")
            app.tabBars.buttons["History"].tap(); capture(app, "\(style)-calendar")
            app.segmentedControls.buttons["Heatmap"].tap(); capture(app, "\(style)-heatmap")
            app.segmentedControls.buttons["List"].tap(); capture(app, "\(style)-history-list")
            app.tabBars.buttons["Journal"].tap(); capture(app, "\(style)-journal")
            app.tabBars.buttons["Trends"].tap(); capture(app, "\(style)-trends")
            app.tabBars.buttons["Today"].tap(); app.buttons["fast.start"].tap(); capture(app, "\(style)-start")
            app.buttons["fast.save"].tap()
            XCTAssertTrue(app.buttons["fast.end"].waitForExistence(timeout: 5)); capture(app, "\(style)-running")
            app.terminate()
        }
    }
    func testLargestText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fast.demo", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch(); XCTAssertTrue(app.tabBars.buttons["History"].waitForExistence(timeout: 15))
        capture(app, "large-today")
        app.tabBars.buttons["History"].tap(); capture(app, "large-calendar")
        app.tabBars.buttons["Journal"].tap(); capture(app, "large-journal")
        app.tabBars.buttons["Trends"].tap(); capture(app, "large-trends")
    }
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
