import XCTest

/// Every screen and sheet of the app, in light and dark, plus the largest text size and the
/// purchase screens. The pictures feed design reviews (polish guide §13): a step that can't reach
/// its screen writes a note instead of failing, so one moved button doesn't cost the rest of the
/// tour. The camera is VisionKit's and the simulator has none, so it isn't in the tour.
@MainActor final class ScreensTourTests: XCTestCase {
    func testLightAndDarkScreens() throws {
        for style in ["light", "dark"] { tour(style) }
    }

    private func tour(_ style: String) {
        let app = XCUIApplication()
        app.launchArguments = ["-scanner.ui", "-scanner.reset", "-scanner.demo", "-scanner.appearance", style]
        app.launch()
        let rows = app.buttons.matching(identifier: "scanner.document")
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 30))
        sleep(2)
        var n = 0
        func shot(_ name: String, _ on: XCUIApplication? = nil) { n += 1; capture(on ?? app, String(format: "%@-%02d-%@", style, n, name)) }

        // Library: grid, the bottom of it, sort, favorites, a card's menu, search
        shot("library")
        app.swipeUp(); sleep(1); shot("library-scrolled"); app.swipeUp(); shot("library-bottom"); top(app)
        if tap(app.buttons["Sort"]) { sleep(1); shot("sort-menu"); dismissMenu(app) }
        let segments = app.segmentedControls.firstMatch
        if segments.waitForExistence(timeout: 3) {
            segments.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5)).tap(); sleep(1); shot("favorites")
            segments.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.5)).tap(); sleep(1)
        } else { note("\(style): no Favorites switch") }
        let card = rows.firstMatch
        if card.exists { card.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).press(forDuration: 1.2); sleep(1); shot("card-menu"); dismissMenu(app) } else { note("\(style): card not hittable for its menu") }
        let search = app.searchFields.firstMatch
        if !search.exists { app.swipeDown() }
        if search.waitForExistence(timeout: 3) {
            search.tap(); sleep(1)
            if !(search.value(forKey: "hasKeyboardFocus") as? Bool ?? false) { search.tap(); sleep(1) }
            if search.value(forKey: "hasKeyboardFocus") as? Bool ?? false {
                app.typeText("receipt"); sleep(1); shot("search")
                app.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 7) + "zzz"); sleep(1); shot("search-empty")
            } else { note("\(style): search field took no focus") }
        } else { note("\(style): no search field") }
        // iOS 26's search bar has no Cancel button; relaunch (keeping the library) to leave search.
        app.terminate(); app.launchArguments = ["-scanner.ui", "-scanner.appearance", style]; app.launch()
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 15)); sleep(1)
        top(app)

        // Settings and Before you buy
        if tap(app.buttons["Settings"]) {
            shot("settings"); app.swipeUp(); shot("settings-2"); app.swipeUp(); shot("settings-3"); top(app)
            if reveal(app, app.buttons["Before you buy"]) { app.buttons["Before you buy"].tap(); sleep(1); shot("before-you-buy"); dismissSheet(app) }
            back(app)
        }
        top(app)

        // Recently Deleted (the demo has one) and Backup, under the grid
        if reveal(app, app.buttons["Recently Deleted"]) {
            app.buttons["Recently Deleted"].tap(); sleep(1); shot("recently-deleted")
            let row = app.buttons["trash.restore"]
            if row.exists { app.cells.firstMatch.swipeLeft(); sleep(1); shot("recently-deleted-swipe"); app.cells.firstMatch.swipeRight() }
            back(app)
        }
        if reveal(app, app.buttons["Backup & restore"]) { app.buttons["Backup & restore"].tap(); sleep(1); shot("backup"); app.swipeUp(); shot("backup-2"); back(app) }
        top(app)

        // A three-page document: pages, chips, text, details; add pages; arrange; a page; preview; export
        let lease = app.buttons.matching(NSPredicate(format: "identifier == 'scanner.document' AND label BEGINSWITH 'Apartment lease'")).firstMatch
        if reveal(app, lease) {
            lease.tap(); sleep(2); shot("document")
            app.swipeUp(); shot("document-2"); app.swipeUp(); shot("document-3"); top(app)
            if reveal(app, app.buttons["scanner.addPages"]) { app.buttons["scanner.addPages"].tap(); sleep(1); shot("add-pages-menu"); dismissMenu(app) }
            top(app)
            let page2 = app.buttons["scanner.page.2"]
            if page2.exists { page2.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).press(forDuration: 1.2); sleep(1); shot("page-menu"); app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.13)).tap(); sleep(1) }
            let arrange = app.buttons.matching(NSPredicate(format: "identifier == 'scanner.arrange' OR label == 'Arrange'")).firstMatch
            if reveal(app, arrange) { arrange.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); sleep(1); shot("arrange"); dismissSheet(app) }
            top(app)
            if reveal(app, app.buttons["scanner.page.1"]) {
                app.buttons["scanner.page.1"].tap(); sleep(2); shot("page-editor")
                app.swipeUp(); shot("page-editor-2"); back(app)
            }
            top(app)
            if reveal(app, app.buttons["scanner.preview"]) {
                app.buttons["scanner.preview"].tap()
                if app.navigationBars["PDF preview"].waitForExistence(timeout: 15) { sleep(1); shot("pdf-preview"); app.navigationBars["PDF preview"].buttons["Done"].tap() }
                else { note("\(style): no PDF preview") }
            }
            if tap(app.buttons["scanner.export"]) {
                sleep(1); shot("export")
                if tap(app.buttons["export.page.2"]) { shot("export-deselected"); app.buttons["export.page.2"].tap() }
                app.swipeUp(); shot("export-2")
                if tap(app.buttons["export.prepare"]) {
                    if app.buttons["export.share"].waitForExistence(timeout: 30) { sleep(1); shot("export-ready"); app.swipeUp(); shot("export-ready-2") }
                    else { note("\(style): export never got ready") }
                }
                dismissSheet(app)
            }
            if reveal(app, app.buttons["Delete document"]) {
                app.buttons["Delete document"].tap(); sleep(1); shot("delete-confirm")
                let cancel = app.buttons["Cancel"]; if cancel.exists && cancel.isHittable { cancel.tap() } else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap(); sleep(1) }
            }
            back(app)
        } else { note("\(style): no lease document") }
        top(app)

        // A one-page document
        let receipt = app.buttons.matching(NSPredicate(format: "identifier == 'scanner.document' AND label BEGINSWITH 'Café receipt'")).firstMatch
        if reveal(app, receipt) { receipt.tap(); sleep(2); shot("document-one-page"); back(app) }
        app.terminate()

        // Empty library
        let empty = XCUIApplication()
        empty.launchArguments = ["-scanner.ui", "-scanner.reset", "-scanner.appearance", style]
        empty.launch(); sleep(3); shot("empty-library", empty)
        empty.terminate()

        // First launch: Before you buy, before the trial starts.
        let fresh = XCUIApplication()
        fresh.launchArguments = ["-scanner.ui", "-scanner.reset", "-scanner.fresh", "-scanner.launchSheets", "-scanner.appearance", style]
        fresh.launch(); sleep(3); shot("first-launch", fresh)
        fresh.terminate()

        // Trial ended at launch, then the unlock sheet from a paid button.
        let expired = XCUIApplication()
        expired.launchArguments = ["-scanner.ui", "-scanner.reset", "-scanner.expired", "-scanner.launchSheets", "-scanner.appearance", style]
        expired.launch(); sleep(4); shot("trial-ended", expired)
        dismissSheet(expired); sleep(1)
        let paid = expired.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Import' OR identifier == 'scanner.scan'")).firstMatch
        if tap(paid) { sleep(1); shot("unlock", expired); dismissSheet(expired) }
        expired.terminate()
    }

    func testLargestText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-scanner.ui", "-scanner.reset", "-scanner.demo", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let rows = app.buttons.matching(identifier: "scanner.document")
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 30)); sleep(2)
        capture(app, "large-01-library")
        app.swipeUp(); capture(app, "large-02-library-scrolled")
        top(app)
        let row = rows.firstMatch
        if reveal(app, row) {
            row.tap(); sleep(2); capture(app, "large-03-document")
            app.swipeUp(); capture(app, "large-04-document-2"); app.swipeUp(); capture(app, "large-05-document-3")
            top(app)
            if tap(app.buttons["scanner.export"]) { sleep(1); capture(app, "large-06-export"); dismissSheet(app) }
        }
    }

    // MARK: Helpers

    /// Attached as it happens, so a note survives a later failure; also printed, because the export
    /// keeps only the last attachment of a name.
    private func note(_ text: String) {
        let a = XCTAttachment(string: text); a.name = "tour-note"; a.lifetime = .keepAlways; add(a)
        print("tour-note: \(text)")
    }

    @discardableResult private func tap(_ element: XCUIElement) -> Bool {
        guard element.waitForExistence(timeout: 4), element.isHittable else { note("missing: \(element)"); return false }
        element.tap(); return true
    }

    /// Scrolls down until the element is on screen.
    @discardableResult private func reveal(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        for _ in 0..<8 {
            if element.exists && element.isHittable {
                // Clear the floating action bar and bottom search field, which sit over the last rows.
                if element.frame.maxY > app.frame.height * 0.72 { app.swipeUp(); sleep(1) }
                return true
            }
            app.swipeUp()
        }
        note("not reached: \(element)")
        return false
    }

    private func top(_ app: XCUIApplication) { for _ in 0..<3 { app.swipeDown() } }

    private func back(_ app: XCUIApplication) {
        let button = app.navigationBars.buttons.element(boundBy: 0)
        if button.exists && button.isHittable { button.tap() } else {
            note("no back button; swiping back")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5)).press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
        }
    }

    private func dismissMenu(_ app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.06)).tap(); sleep(1)
    }

    private func dismissSheet(_ app: XCUIApplication) {
        for label in ["Not now", "Close", "Done", "Cancel", "Look around first"] where app.buttons[label].exists && app.buttons[label].isHittable {
            app.buttons[label].tap(); sleep(1); return
        }
        app.swipeDown(velocity: .fast); sleep(1)
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
