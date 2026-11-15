import XCTest

@MainActor final class WidgetTourTests: XCTestCase {
    func testHomeWidgetReadsRunningTimer() throws {
        let app = XCUIApplication(); app.launchArguments = ["-fast.ui", "-fast.reset"]; app.launch()
        XCTAssertTrue(app.buttons["fast.start"].waitForExistence(timeout: 15))
        app.buttons["fast.start"].tap(); app.buttons["fast.save"].tap()
        XCTAssertTrue(app.buttons["fast.end"].waitForExistence(timeout: 5))
        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let icon = springboard.icons["Fast Tracker"].firstMatch
        XCTAssertTrue(icon.waitForExistence(timeout: 5))
        icon.press(forDuration: 1.5)
        XCTAssertTrue(springboard.buttons["Small widget"].waitForExistence(timeout: 5))
        springboard.buttons["Small widget"].tap()
        XCTAssertTrue(springboard.staticTexts["Planned finish"].waitForExistence(timeout: 20))
        let tree = XCTAttachment(string: springboard.debugDescription); tree.name = "widget-running-tree"; tree.lifetime = .keepAlways; add(tree)
        let screenshot = XCTAttachment(screenshot: springboard.screenshot()); screenshot.name = "widget-running"; screenshot.lifetime = .keepAlways; add(screenshot)
        springboard.staticTexts["Planned finish"].press(forDuration: 1.5)
        XCTAssertTrue(springboard.buttons["App icon"].waitForExistence(timeout: 5))
        springboard.buttons["App icon"].tap()
        XCUIDevice.shared.press(.home)
    }
}
