import XCTest
@MainActor final class SmokeTests: XCTestCase {
    private func launch(audit: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-scanner.ui", "-scanner.reset", "-scanner.seed"] + (audit ? ["-scanner.audit"] : [])
        app.launch(); return app
    }
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, down: Bool = false) {
        for _ in 0..<7 {
            if element.exists && element.isHittable { return }
            let list = app.collectionViews.element(boundBy: max(0, app.collectionViews.count - 1))
            if down { list.swipeDown() } else { list.swipeUp() }
        }
        XCTAssertTrue(element.exists && element.isHittable, "Could not reveal \(element)")
    }
    func testBackupOffersFilesExport() throws {
        let app = launch()
        XCTAssertTrue(app.buttons.matching(identifier: "scanner.document").firstMatch.waitForExistence(timeout: 30))
        let backup = app.buttons["Backup & restore"]
        reveal(backup, in: app, down: true); backup.tap()
        XCTAssertTrue(app.buttons["backup.export"].waitForExistence(timeout: 10))
        app.buttons["backup.export"].tap()
        XCTAssertTrue(app.buttons["Save"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["Save"].isEnabled)
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "scanner-backup-files"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.terminate()
    }
    func testCreateRecognizeRenamePreviewAndRelaunch() throws {
        let app = launch()
        let first = app.buttons.matching(identifier: "scanner.document").firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 30)); reveal(first, in: app); first.tap()
        let title = app.textFields["scanner.title"]; XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (title.value as? String ?? "").count) + "Receipt test")
        app.buttons["Save name"].tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] 'Coffee'")).firstMatch.waitForExistence(timeout: 10))
        reveal(app.buttons["Preview PDF"], in: app); app.buttons["Preview PDF"].tap()
        XCTAssertTrue(app.navigationBars["PDF preview"].waitForExistence(timeout: 10))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "scanner-pdf"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.navigationBars["PDF preview"].buttons["Done"].tap(); app.terminate()
        app.launchArguments = ["-scanner.ui"]; app.launch()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Receipt test'")).firstMatch.waitForExistence(timeout: 15))
    }
    func testSelectedExportAndTrashRestore() throws {
        let app = launch()
        let row = app.buttons.matching(identifier: "scanner.document").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30)); reveal(row, in: app); row.tap()
        reveal(app.buttons["scanner.export"], in: app); app.buttons["scanner.export"].tap()
        XCTAssertTrue(app.switches["export.page.2"].waitForExistence(timeout: 5))
        let pageTwo = app.switches["export.page.2"]
        pageTwo.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertEqual(pageTwo.value as? String, "0")
        reveal(app.buttons["export.prepare"], in: app); app.buttons["export.prepare"].tap()
        reveal(app.buttons["export.share"], in: app)
        XCTAssertTrue(app.staticTexts["export.size"].exists)
        reveal(app.segmentedControls.buttons["Smaller file"], in: app, down: true)
        app.segmentedControls.buttons["Smaller file"].tap()
        reveal(app.buttons["export.prepare"], in: app); app.buttons["export.prepare"].tap()
        reveal(app.buttons["export.share"], in: app)
        reveal(app.buttons["Preview"], in: app); app.buttons["Preview"].tap()
        XCTAssertTrue(app.navigationBars["PDF preview"].waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "scanner-selected-pdf"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.navigationBars["PDF preview"].buttons["Done"].tap(); app.navigationBars["Export PDF"].buttons["Done"].tap()
        reveal(app.buttons["Delete document"], in: app); app.buttons["Delete document"].tap()
        app.buttons["Move to Recently Deleted"].tap()
        XCTAssertTrue(app.navigationBars["Documents"].waitForExistence(timeout: 5))
        reveal(app.buttons["Recently Deleted"], in: app, down: true); app.buttons["Recently Deleted"].tap()
        XCTAssertTrue(app.buttons["trash.restore"].waitForExistence(timeout: 5)); app.buttons["trash.restore"].tap()
        XCTAssertTrue(app.staticTexts["Nothing in Recently Deleted"].waitForExistence(timeout: 5))
        app.terminate(); app.launchArguments = ["-scanner.ui"]; app.launch()
        XCTAssertTrue(row.waitForExistence(timeout: 15))
    }
    func testZZPageAdjustmentsAndProcessingAudit() throws {
        let app = launch(audit: true)
        let audit = app.buttons["Run processing audit"]
        XCTAssertTrue(audit.waitForExistence(timeout: 15))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: audit)
        waitForExpectations(timeout: 30); audit.tap()
        let result = app.staticTexts["audit.result"]
        expectation(for: NSPredicate(format: "label BEGINSWITH 'PASS:' OR label BEGINSWITH 'FAIL:'"), evaluatedWith: result)
        waitForExpectations(timeout: 180)
        XCTAssertTrue(result.label.hasPrefix("PASS:"), result.label)
        let row = app.buttons.matching(identifier: "scanner.document").firstMatch
        reveal(row, in: app); row.tap()
        app.buttons["scanner.page.1"].tap()
        XCTAssertTrue(app.buttons["page.rotate"].waitForExistence(timeout: 10)); app.buttons["page.rotate"].tap()
        let grayscale = app.switches["page.grayscale"]
        reveal(grayscale, in: app)
        grayscale.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertEqual(grayscale.value as? String, "1")
        app.buttons["page.save"].tap()
        XCTAssertTrue(app.textFields["scanner.title"].waitForExistence(timeout: 10))
        app.terminate(); app.launchArguments = ["-scanner.ui"]; app.launch()
        XCTAssertTrue(row.waitForExistence(timeout: 15)); reveal(row, in: app); row.tap(); app.buttons["scanner.page.1"].tap()
        XCTAssertTrue(grayscale.waitForExistence(timeout: 10)); XCTAssertEqual(grayscale.value as? String, "1")
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "scanner-page-editor"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
}
