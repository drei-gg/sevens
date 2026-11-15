import XCTest

/// Every screen and sheet of the iPhone app, in light and dark, plus the largest text size and the
/// purchase screens. The pictures feed design reviews (polish guide §13): a step that can't reach
/// its screen writes a note instead of failing, so one moved button doesn't cost the rest of the
/// tour.
@MainActor final class ScreensTourTests: XCTestCase {
    func testLightAndDarkScreens() throws {
        for style in ["light", "dark"] { tour(style) }
    }

    private func tour(_ style: String) {
        let look = style == "dark" ? "-fast.dark" : "-fast.light"
        let app = XCUIApplication()
        app.launchArguments = ["-fast.demo", look]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Today"].waitForExistence(timeout: 15))
        var n = 0
        func shot(_ on: XCUIApplication, _ name: String) { n += 1; capture(on, String(format: "%@-%02d-%@", style, n, name)) }

        // Today
        shot(app, "today")
        app.swipeUp(); shot(app, "today-scrolled"); top(app)

        // Water, from Today's card
        if reveal(app, app.buttons["water.open"]) {
            app.buttons["water.open"].tap(); shot(app, "water")
            if tap(app.buttons["water.customAdd"]) { shot(app, "water-log-drink"); cancelSheet(app) }
            if tap(app.buttons["water.settings"]) {
                shot(app, "water-settings"); app.swipeUp(); shot(app, "water-settings-scrolled"); back(app)
            }
            if reveal(app, app.buttons["water.trends"]) { app.buttons["water.trends"].tap(); shot(app, "water-trends"); back(app) }
            if reveal(app, app.buttons["All water entries"]) { app.buttons["All water entries"].tap(); shot(app, "water-all-entries"); back(app) }
            back(app)
        }
        top(app)

        // Plans, the health note and a check-in, from Today
        if reveal(app, app.buttons["Plans & presets"]) {
            app.buttons["Plans & presets"].tap(); shot(app, "plans")
            if tap(app.buttons["Plan a fast"]) { shot(app, "plan-editor"); cancelSheet(app) }
            if reveal(app, app.buttons["Save a custom preset"]) { app.buttons["Save a custom preset"].tap(); shot(app, "preset-editor"); cancelSheet(app) }
            back(app)
        }
        top(app)
        if reveal(app, app.buttons["Check in"]) { app.buttons["Check in"].tap(); shot(app, "check-in"); cancelSheet(app) }
        top(app)
        if reveal(app, app.buttons["About fasting & this tracker"]) {
            app.buttons["About fasting & this tracker"].tap(); shot(app, "about-fasting"); back(app)
        }
        top(app)

        // Settings and everything under it
        if tap(app.navigationBars.buttons["Settings"]) {
            shot(app, "settings")
            app.swipeUp(); shot(app, "settings-2"); app.swipeUp(); shot(app, "settings-3"); app.swipeUp(); shot(app, "settings-4")
            top(app)
            for (label, name) in [("Reminders", "reminders"), ("Export, import & delete", "your-data"), ("Apple Health", "apple-health"),
                                  ("Network contact", "network-contact"), ("Notices", "notices")] {
                if reveal(app, app.buttons[label]) { app.buttons[label].tap(); shot(app, name); back(app); top(app) }
            }
            let whatsNew = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'What'")).firstMatch
            if reveal(app, whatsNew) { whatsNew.tap(); shot(app, "whats-new"); back(app); top(app) }
            if reveal(app, app.buttons["Before you buy"]) { app.buttons["Before you buy"].tap(); shot(app, "before-you-buy"); dismissSheet(app) }
            back(app)
        }

        // History: calendar, heatmap, list, a fast's details, add a past fast
        app.tabBars.buttons["History"].tap(); shot(app, "calendar")
        app.swipeUp(); shot(app, "calendar-day"); top(app)
        app.segmentedControls.buttons["Heatmap"].tap(); shot(app, "heatmap")
        app.segmentedControls.buttons["List"].tap(); shot(app, "history-list")
        let row = app.collectionViews.cells.element(boundBy: 1)
        if row.waitForExistence(timeout: 3) { row.tap(); shot(app, "fast-details"); back(app) } else { note("\(style): no history row") }
        if tap(app.navigationBars.buttons["Add past fast"]) { shot(app, "add-past-fast"); cancelSheet(app) }
        app.segmentedControls.buttons["Calendar"].tap()

        // Journal: list, an entry, a new entry
        app.tabBars.buttons["Journal"].tap(); shot(app, "journal")
        let entry = app.collectionViews.cells.element(boundBy: 1)
        if entry.waitForExistence(timeout: 3) { entry.tap(); shot(app, "journal-entry"); back(app) } else { note("\(style): no journal entry") }
        let newEntry = app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", ["New journal entry", "square.and.pencil"])).firstMatch
        if tap(newEntry) { shot(app, "journal-new"); cancelSheet(app) }

        // Trends, and a measurement editor
        app.tabBars.buttons["Trends"].tap(); shot(app, "trends")
        app.swipeUp(); shot(app, "trends-2"); app.swipeUp(); shot(app, "trends-3")
        let addMeasurement = app.buttons["Add measurement"].firstMatch
        if reveal(app, addMeasurement) { addMeasurement.tap(); shot(app, "measurement-editor"); cancelSheet(app) }

        // A fast: start sheet, running, edit, end sheet, then the eating window it leaves
        // After Trends' long scroll the Today tab reports no hit point, so relaunch: the app opens on Today.
        app.terminate(); app.launch()
        XCTAssertTrue(app.tabBars.buttons["Today"].waitForExistence(timeout: 15))
        if reveal(app, app.buttons["fast.start"]), tap(app.buttons["fast.start"]) {
            shot(app, "start")
            app.buttons["fast.save"].tap()
            if app.buttons["fast.end"].waitForExistence(timeout: 5) {
                shot(app, "running")
                app.swipeUp(); shot(app, "running-scrolled"); top(app)
                if tap(app.buttons["fast.editActive"]) { shot(app, "edit-running"); cancelSheet(app) }
                // Save the end, so Today shows the eating-window countdown of the fast just finished.
                if tap(app.buttons["fast.end"]) {
                    shot(app, "end-fast"); app.buttons["fast.save"].tap()
                    if app.buttons["fast.start"].waitForExistence(timeout: 5) { top(app); shot(app, "eating-window") }
                    else { note("\(style): the fast didn't end") }
                }
            } else { note("\(style): the fast didn't start") }
        }
        app.terminate()

        // First launch: Before you buy, before the trial starts.
        let fresh = XCUIApplication()
        fresh.launchArguments = ["-fast.ui", "-fast.reset", "-fast.fresh", "-fast.launchSheets", look]
        fresh.launch(); sleep(3); shot(fresh, "first-launch")
        fresh.terminate()

        // Trial ended at launch, then the unlock sheet from a paid button.
        let expired = XCUIApplication()
        expired.launchArguments = ["-fast.ui", "-fast.reset", "-fast.expired", "-fast.launchSheets", look]
        expired.launch(); sleep(3); shot(expired, "trial-ended")
        dismissSheet(expired)
        if tap(expired.buttons["fast.start"]) { sleep(1); shot(expired, "unlock"); dismissSheet(expired) }
        expired.terminate()
    }

    func testLargestText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fast.demo", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch(); XCTAssertTrue(app.tabBars.buttons["History"].waitForExistence(timeout: 15))
        capture(app, "large-01-today")
        app.swipeUp(); capture(app, "large-02-today-scrolled")
        app.tabBars.buttons["History"].tap(); capture(app, "large-03-calendar")
        app.tabBars.buttons["Journal"].tap(); capture(app, "large-04-journal")
        app.tabBars.buttons["Trends"].tap(); capture(app, "large-05-trends")
    }

    // MARK: Helpers

    /// Attached as it happens, so a note survives a later failure.
    private func note(_ text: String) {
        let a = XCTAttachment(string: text); a.name = "tour-note"; a.lifetime = .keepAlways; add(a)
    }

    @discardableResult private func tap(_ element: XCUIElement) -> Bool {
        guard element.waitForExistence(timeout: 4), element.isHittable else { note("missing: \(element)"); return false }
        element.tap(); return true
    }

    /// Scrolls down until the element is on screen.
    @discardableResult private func reveal(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return true }
            app.swipeUp()
        }
        note("not reached: \(element)")
        return false
    }

    private func top(_ app: XCUIApplication) { for _ in 0..<4 { app.swipeDown() } }

    private func back(_ app: XCUIApplication) {
        let button = app.navigationBars.buttons.element(boundBy: 0)
        if button.exists && button.isHittable { button.tap() } else { note("no back button") }
    }

    private func cancelSheet(_ app: XCUIApplication) {
        let cancel = app.buttons["Cancel"]
        if cancel.waitForExistence(timeout: 2) && cancel.isHittable { cancel.tap() } else { dismissSheet(app) }
    }

    private func dismissSheet(_ app: XCUIApplication) {
        for label in ["Not now", "Close", "Done", "Look around first"] where app.buttons[label].exists && app.buttons[label].isHittable {
            app.buttons[label].tap(); return
        }
        app.swipeDown(velocity: .fast)
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
