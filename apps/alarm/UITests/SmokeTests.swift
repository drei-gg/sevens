import XCTest
@MainActor final class SmokeTests: XCTestCase {
    func testImportedSoundPreviewPickerCancelAndMissingFallback() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-alarm.ui", "-alarm.reset", "-alarm.sound"]; app.launch()
        let edit = app.buttons["edit-Imported sound"]
        XCTAssertTrue(edit.waitForExistence(timeout: 15)); edit.tap()
        let imported = app.staticTexts["imported-sound"]
        for _ in 0..<7 { if imported.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(imported.exists)
        app.buttons["Preview imported sound"].tap()
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Imported sound"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["import-sound"].tap()
        let picker = app.navigationBars["FullDocumentManagerViewControllerNavigationBar"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10)); picker.buttons["Cancel"].tap()
        XCTAssertTrue(picker.wait(for: \.exists, toEqual: false, timeout: 5))
        XCTAssertTrue(imported.waitForExistence(timeout: 5))
        let cancelShot = XCTAttachment(screenshot: app.screenshot()); cancelShot.name = "After picker cancellation"; cancelShot.lifetime = .keepAlways; add(cancelShot)
        XCTAssertFalse(app.alerts.firstMatch.exists, app.debugDescription)
        XCTAssertTrue(app.buttons["Use built-in tone"].wait(for: \.isHittable, toEqual: true, timeout: 5))
        app.buttons["Use built-in tone"].tap(); app.buttons["save-routine"].tap()
        XCTAssertTrue(edit.waitForExistence(timeout: 10))
        app.terminate(); app.launchArguments = ["-alarm.ui", "-alarm.reset", "-alarm.sound", "-alarm.sound-missing"]; app.launch()
        XCTAssertTrue(edit.waitForExistence(timeout: 15)); edit.tap()
        let missing = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH %@", "This audio file is missing.")).firstMatch
        for _ in 0..<7 { if missing.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(missing.exists)
        let missingShot = XCTAttachment(screenshot: app.screenshot()); missingShot.name = "Missing sound fallback"; missingShot.lifetime = .keepAlways; add(missingShot)
        app.buttons["Use built-in tone"].tap(); app.buttons["save-routine"].tap()
        XCTAssertTrue(edit.waitForExistence(timeout: 10))
    }
    func testRoutineTemplateConsent() {
        let app = XCUIApplication(); app.launchArguments = ["-alarm.ui", "-alarm.reset"]; app.launch()
        XCTAssertTrue(app.buttons["add-routine"].waitForExistence(timeout: 15)); app.buttons["add-routine"].tap()
        app.buttons["save-routine"].tap()
        let row = app.buttons["edit-Wake up"]
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        row.press(forDuration: 1)
        XCTAssertTrue(app.buttons["Save routine template"].waitForExistence(timeout: 5))
        app.buttons["Save routine template"].tap()
        XCTAssertTrue(app.buttons["Save template"].waitForExistence(timeout: 5))
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "template-consent"; image.lifetime = .keepAlways; add(image)
        // Dismiss the native confirmation popover without exporting or mutating the routine.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.8)).tap()
        XCTAssertTrue(app.switches["routine-Wake up"].exists)
        XCTAssertFalse(app.buttons["Save template"].exists)
    }
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
    // Every screen, light and dark, plus the main screens at an accessibility text size. Each step
    // is soft: a miss prints "not reached:" (grep the log) and the tour carries on. Each area starts
    // from a fresh launch, because after a long scroll the tab bar minimises and tab taps miss.
    var mode = "light"
    /// Rows sit under the next-alarm dial: scroll until the element is on screen.
    func reveal(_ element:XCUIElement) {
        var tries = 0
        while !(element.exists && element.isHittable) && tries < 4 { XCUIApplication().swipeUp(velocity:.slow); sleep(1); tries += 1 }
    }
    func launch(_ extra:[String] = []) -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["-alarm.demo","-alarm.\(mode)"] + extra; app.launch()
        if !app.buttons["add-routine"].waitForExistence(timeout:15) { print("not reached: launch (\(mode))") }
        sleep(1); return app
    }
    func step(_ element:XCUIElement,_ what:String,timeout:Double = 5) -> Bool {
        _ = element.waitForExistence(timeout:timeout)
        reveal(element)
        if element.exists && element.isHittable { element.tap(); sleep(1); return true }
        print("not reached: \(what) (\(mode))"); return false
    }
    func testScreens() {
        for m in ["light","dark"] {
            mode = m
            var app = launch()
            XCTAssertTrue(app.navigationBars["Alarms"].exists)
            capture(app,"alarms")
            app.swipeUp(); sleep(1); capture(app,"alarms-scrolled")
            app.terminate(); app = launch()
            if step(app.buttons["custom-nap"],"custom nap") { capture(app,"custom-nap"); _ = step(app.buttons["Cancel"],"custom nap cancel") }
            if step(app.buttons["add-routine"],"new alarm") { capture(app,"editor-new-alarm"); app.swipeUp(); sleep(1); capture(app,"editor-new-alarm-scrolled"); app.swipeUp(); sleep(1); capture(app,"editor-new-alarm-end"); _ = step(app.buttons["Cancel"],"new alarm cancel") }
            app.terminate(); app = launch()
            if step(app.buttons["edit-Wake up"],"edit alarm") { capture(app,"editor-alarm"); _ = step(app.buttons["Cancel"],"edit alarm cancel") }
            if step(app.buttons["try-mission"].firstMatch,"try mission") { capture(app,"mission-arithmetic"); _ = step(app.buttons["Close"],"mission close") }
            app.terminate(); app = launch()
            if step(app.tabBars.buttons["Loops"],"loops tab") { capture(app,"loops")
                if step(app.buttons["edit-Workday check-in"],"edit loop") { capture(app,"editor-loop"); app.swipeUp(); sleep(1); capture(app,"editor-loop-scrolled"); _ = step(app.buttons["Cancel"],"edit loop cancel") }
                if step(app.buttons["add-routine"],"new loop") { capture(app,"editor-new-loop"); _ = step(app.buttons["Cancel"],"new loop cancel") } }
            app.terminate(); app = launch()
            if step(app.tabBars.buttons["History"],"history tab") { capture(app,"history"); app.swipeUp(); sleep(1); capture(app,"history-scrolled")
                if step(app.buttons.containing(NSPredicate(format:"label BEGINSWITH %@","Wake up")).firstMatch,"history record") { capture(app,"event-editor"); _ = step(app.buttons["Cancel"],"event editor cancel") } }
            app.terminate(); app = launch()
            if step(app.tabBars.buttons["Settings"],"settings tab") { capture(app,"settings"); app.swipeUp(); sleep(1); capture(app,"settings-scrolled"); app.swipeUp(); sleep(1); capture(app,"settings-end") }
            app.terminate(); app = launch()
            if step(app.tabBars.buttons["Settings"],"settings tab 2") { let d = app.buttons["New alarm defaults"]; if !d.isHittable { app.swipeUp() }; if step(d,"defaults") { capture(app,"alarm-defaults") } }
            app.terminate()
            let titles = ["typing":"Type a phrase","memory":"Memory tiles","shake":"Shake","walk":"Walk","numberOrder":"Number order","colorWord":"Color clash","code":"Scan a code"]
            for mission in ["typing","memory","shake","walk","numberOrder","colorWord","code"] {
                app = launch(["-alarm.mission.\(mission)"])
                // Wake up's chip, by its mission (Gym's walk chip sits further down).
                let chip = app.buttons.matching(NSPredicate(format:"identifier == 'try-mission' AND label CONTAINS %@",titles[mission]!)).firstMatch
                if step(chip,"try \(mission)") { capture(app,"mission-\(mission)") }
                app.terminate()
            }
        }
        // Accessibility text size: the dial steps aside and the rows reflow.
        mode = "light"
        var app = launch(["-UIPreferredContentSizeCategoryName","UICTContentSizeCategoryAccessibilityXL"])
        capture(app,"ax-alarms")
        app.terminate(); app = launch(["-UIPreferredContentSizeCategoryName","UICTContentSizeCategoryAccessibilityXL"])
        if step(app.tabBars.buttons["History"],"ax history tab") { capture(app,"ax-history") }
        app.terminate()
    }
    func capture(_ app:XCUIApplication,_ name:String) { let a = XCTAttachment(screenshot:app.screenshot()); a.name = "\(name)-\(mode)"; a.lifetime = .keepAlways; add(a) }
}


@MainActor final class ChallengeUITests: XCTestCase {
    func testNumberOrderCompletes() {
        let app = XCUIApplication(); app.launchArguments = ["-alarm.demo","-alarm.mission.numberOrder"]; app.launch()
        XCTAssertTrue(app.buttons["add-routine"].waitForExistence(timeout:15)); let chip = app.buttons["try-mission"].firstMatch; var n = 0; while !(chip.exists && chip.isHittable) && n < 4 { app.swipeUp(velocity:.slow); n += 1 }; chip.tap()
        for n in 1...6 { let tile = app.buttons["order-\(n)"]; XCTAssertTrue(tile.waitForExistence(timeout:5)); tile.tap() }
        XCTAssertTrue(app.staticTexts["Done."].waitForExistence(timeout:5))
    }
    func testMemoryAndColorPreviews() {
        for mission in ["memory","colorWord","shake","walk"] {
            let app = XCUIApplication(); app.launchArguments = ["-alarm.demo","-alarm.mission.\(mission)"]; app.launch()
            XCTAssertTrue(app.buttons["add-routine"].waitForExistence(timeout:15)); let chip = app.buttons["try-mission"].firstMatch; var n = 0; while !(chip.exists && chip.isHittable) && n < 4 { app.swipeUp(velocity:.slow); n += 1 }; chip.tap()
            XCTAssertTrue(app.navigationBars["Try mission"].waitForExistence(timeout:5))
            if mission == "memory" { XCTAssertTrue(app.buttons["memory-tile-0"].waitForExistence(timeout:5)) }
            if mission == "colorWord" { XCTAssertTrue(app.buttons["Blue"].waitForExistence(timeout:5)) }
            if mission == "shake" || mission == "walk" { XCTAssertTrue(app.buttons["Start challenge"].waitForExistence(timeout:5)) }
            let image = XCTAttachment(screenshot:app.screenshot()); image.name = "challenge-\(mission)"; image.lifetime = .keepAlways; add(image)
            app.terminate()
        }
    }
}
