import XCTest
@MainActor final class SmokeTests: XCTestCase {
    func testCustomNapPersists() {
        let app = XCUIApplication(); app.launchArguments = ["-alarm.ui","-alarm.reset"]; app.launch()
        XCTAssertTrue(app.buttons["custom-nap"].waitForExistence(timeout:15)); app.buttons["custom-nap"].tap()
        XCTAssertTrue(app.buttons["start-custom-nap"].waitForExistence(timeout:5))
        let image = XCTAttachment(screenshot:app.screenshot()); image.name = "custom-nap"; image.lifetime = .keepAlways; add(image)
        app.buttons["start-custom-nap"].tap()
        XCTAssertTrue(app.switches["routine-25-minute nap"].waitForExistence(timeout:8))
        app.terminate(); app.launchArguments = ["-alarm.ui"]; app.launch()
        XCTAssertTrue(app.switches["routine-25-minute nap"].waitForExistence(timeout:10))
    }
    func testWeekdayPresetAndDefaultsScreen() {
        let app = XCUIApplication(); app.launchArguments = ["-alarm.ui","-alarm.reset"]; app.launch()
        XCTAssertTrue(app.buttons["add-routine"].waitForExistence(timeout:15)); app.buttons["add-routine"].tap()
        app.buttons["Choose days"].tap(); app.buttons["Weekends"].tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format:"label CONTAINS %@", "Sun · Sat")).firstMatch.exists)
        app.buttons["save-routine"].tap()
        app.tabBars.buttons["Settings"].tap()
        let defaults = app.buttons["New alarm defaults"]
        if !defaults.isHittable { app.swipeUp() }
        XCTAssertTrue(defaults.waitForExistence(timeout:5)); defaults.tap()
        XCTAssertTrue(app.buttons["save-alarm-defaults"].waitForExistence(timeout:5))
        let image = XCTAttachment(screenshot:app.screenshot()); image.name = "alarm-defaults"; image.lifetime = .keepAlways; add(image)
        app.buttons["save-alarm-defaults"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout:5))
    }
    func testCreateRoutineAndRelaunch() {
        let app = XCUIApplication(); app.launchArguments = ["-alarm.ui","-alarm.reset"]; app.launch()
        XCTAssertTrue(app.buttons["add-routine"].waitForExistence(timeout:15)); app.buttons["add-routine"].tap()
        let name = app.textFields["routine-name"]; XCTAssertTrue(name.waitForExistence(timeout:5)); 
        app.buttons["save-routine"].tap()
        XCTAssertTrue(app.switches["routine-Wake up"].waitForExistence(timeout:8))
        app.terminate(); app.launchArguments = ["-alarm.ui"]; app.launch()
        XCTAssertTrue(app.switches["routine-Wake up"].waitForExistence(timeout:10))
    }
}
@MainActor final class ScreensTourTests: XCTestCase {
    func testScreens() {
        for mode in ["light","dark"] {
            let app = XCUIApplication(); app.launchArguments = ["-alarm.demo","-alarm.\(mode)"]; app.launch()
            XCTAssertTrue(app.buttons["add-routine"].waitForExistence(timeout:15))
            XCTAssertTrue(app.navigationBars["Alarms"].waitForExistence(timeout:5))
            capture(app,"alarms-\(mode)")
            app.tabBars.buttons["Loops"].tap()
            XCTAssertTrue(app.navigationBars["Time loops"].waitForExistence(timeout:5)); capture(app,"loops-\(mode)")
            app.buttons["Edit"].firstMatch.tap()
            XCTAssertTrue(app.navigationBars["Edit routine"].waitForExistence(timeout:5)); capture(app,"editor-\(mode)"); app.buttons["Cancel"].tap()
            app.tabBars.buttons["History"].tap()
            XCTAssertTrue(app.navigationBars["History"].waitForExistence(timeout:5)); capture(app,"history-\(mode)")
            app.tabBars.buttons["Settings"].tap()
            XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout:5)); capture(app,"settings-\(mode)")
            app.terminate()
        }
    }
    func capture(_ app:XCUIApplication,_ name:String) { let a = XCTAttachment(screenshot:app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a) }
}


@MainActor final class ChallengeUITests: XCTestCase {
    func testNumberOrderCompletes() {
        let app = XCUIApplication(); app.launchArguments = ["-alarm.demo","-alarm.mission.numberOrder"]; app.launch()
        XCTAssertTrue(app.buttons["Try mission"].waitForExistence(timeout:15)); app.buttons["Try mission"].tap()
        for n in 1...6 { let tile = app.buttons["order-\(n)"]; XCTAssertTrue(tile.waitForExistence(timeout:5)); tile.tap() }
        XCTAssertTrue(app.staticTexts["Done."].waitForExistence(timeout:5))
    }
    func testMemoryAndColorPreviews() {
        for mission in ["memory","colorWord","shake","walk"] {
            let app = XCUIApplication(); app.launchArguments = ["-alarm.demo","-alarm.mission.\(mission)"]; app.launch()
            XCTAssertTrue(app.buttons["Try mission"].waitForExistence(timeout:15)); app.buttons["Try mission"].tap()
            XCTAssertTrue(app.navigationBars["Try mission"].waitForExistence(timeout:5))
            if mission == "memory" { XCTAssertTrue(app.buttons["memory-tile-0"].waitForExistence(timeout:5)) }
            if mission == "colorWord" { XCTAssertTrue(app.buttons["Blue"].waitForExistence(timeout:5)) }
            if mission == "shake" || mission == "walk" { XCTAssertTrue(app.buttons["Start challenge"].waitForExistence(timeout:5)) }
            let image = XCTAttachment(screenshot:app.screenshot()); image.name = "challenge-\(mission)"; image.lifetime = .keepAlways; add(image)
            app.terminate()
        }
    }
}
