import XCTest

@MainActor final class WidgetTests: XCTestCase {
    private func findHomePage(_ springboard: XCUIApplication) {
        // A previous run can leave SpringBoard in App Library. Start from the first page.
        XCUIDevice.shared.press(.home)
        for _ in 0..<4 { springboard.swipeRight() }
        for _ in 0..<6 {
            let candidates = springboard.icons.matching(identifier: "Alarm & Loops").allElementsBoundByIndex
                + springboard.staticTexts.matching(identifier: "Saved schedule").allElementsBoundByIndex
            if candidates.contains(where: { $0.exists && !$0.frame.isEmpty && springboard.frame.contains($0.frame) }) { return }
            springboard.swipeLeft()
        }
        XCTFail("Alarm icon or widget was not visible on a Home Screen page")
    }
    private func restoreIconIfNeeded() {
        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        findHomePage(springboard)
        let title = springboard.staticTexts["Saved schedule"].firstMatch
        if title.isHittable {
            title.press(forDuration: 1.5)
            if springboard.buttons["App icon"].waitForExistence(timeout: 5) { springboard.buttons["App icon"].tap() }
            XCUIDevice.shared.press(.home)
        }
    }
    func testHomeWidgetReadsSavedNapAndOpensTheApp() throws {
        continueAfterFailure = false
        restoreIconIfNeeded()
        let app = XCUIApplication(); app.launchArguments = ["-alarm.ui", "-alarm.reset"]; app.launch()
        XCTAssertTrue(app.buttons["custom-nap"].waitForExistence(timeout: 15))
        app.buttons["custom-nap"].tap(); app.buttons["start-custom-nap"].tap()
        XCTAssertTrue(app.switches["routine-25-minute nap"].waitForExistence(timeout: 10))
        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        findHomePage(springboard)
        let icon = springboard.icons["Alarm & Loops"].firstMatch
        XCTAssertTrue(icon.waitForExistence(timeout: 5))
        icon.press(forDuration: 1.5)
        XCTAssertTrue(springboard.buttons["Small widget"].waitForExistence(timeout: 5))
        springboard.buttons["Small widget"].tap()
        let name = springboard.staticTexts["25-minute nap"].firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 25))
        let image = XCTAttachment(screenshot: springboard.screenshot()); image.name = "alarm-home-widget"; image.lifetime = .keepAlways; add(image)
        let tree = XCTAttachment(string: springboard.debugDescription); tree.name = "alarm-widget-tree"; tree.lifetime = .keepAlways; add(tree)
        name.tap()
        XCTAssertTrue(app.switches["routine-25-minute nap"].waitForExistence(timeout: 10))
        app.switches["routine-25-minute nap"].tap()
        XCUIDevice.shared.press(.home)
        findHomePage(springboard)
        XCTAssertTrue(springboard.staticTexts["No upcoming times"].waitForExistence(timeout: 25))
        let empty = XCTAttachment(screenshot: springboard.screenshot()); empty.name = "alarm-home-widget-disabled"; empty.lifetime = .keepAlways; add(empty)
        springboard.staticTexts["No upcoming times"].firstMatch.press(forDuration: 1.5)
        XCTAssertTrue(springboard.buttons["App icon"].waitForExistence(timeout: 5))
        springboard.buttons["App icon"].tap()
        XCUIDevice.shared.press(.home)
    }
}
