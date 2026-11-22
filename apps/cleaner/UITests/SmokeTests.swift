import XCTest
@MainActor final class SmokeTests: XCTestCase {
    private func cleanupVideo(_ app: XCUIApplication) {
        app.terminate(); app.launchArguments = ["-cleaner.ui", "-cleaner.video-cleanup"]; app.launch()
        let confirmation = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Delete")).firstMatch
        if confirmation.waitForExistence(timeout: 5) { confirmation.tap() }
        XCTAssertTrue(app.staticTexts["Video fixture ready"].waitForExistence(timeout: 20))
    }
    func testVideoReceiptAudit() {
        continueAfterFailure = false
        let app = XCUIApplication(); defer { cleanupVideo(app) }
        app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.video-audit"]; app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow Full Access"]
        if allow.waitForExistence(timeout: 5) { allow.tap() }
        XCTAssertTrue(app.staticTexts["Video audit passed"].waitForExistence(timeout: 90), app.debugDescription)
    }
    func testVideoRangeReviewAndSave() {
        continueAfterFailure = false
        let app = XCUIApplication(); defer { cleanupVideo(app) }
        app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.video-fixture"]; app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow Full Access"]
        if allow.waitForExistence(timeout: 5) { allow.tap() }
        XCTAssertTrue(app.tabBars.buttons["Videos"].waitForExistence(timeout: 30)); app.tabBars.buttons["Videos"].tap()
        let row = app.staticTexts["video-profile.mp4"]
        XCTAssertTrue(row.waitForExistence(timeout: 15)); row.tap()
        let edit = app.buttons["Trim or compress"]
        for _ in 0..<5 { if edit.isHittable { break }; app.swipeUp() }; edit.tap()
        let start = app.textFields["video-start"], end = app.textFields["video-end"]
        XCTAssertTrue(start.waitForExistence(timeout: 30))
        func replace(_ field: XCUIElement, _ value: String) {
            field.tap(); let count = (field.value as? String)?.count ?? 0
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: count) + value)
        }
        replace(start, "1.250"); replace(end, "4.750")
        app.buttons["video-prepare"].tap()
        XCTAssertTrue(app.staticTexts["video-result"].waitForExistence(timeout: 30), app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "video-range-preview"; attachment.lifetime = .keepAlways; add(attachment)
        app.buttons["video-save"].tap()
        XCTAssertTrue(app.navigationBars["Video copy saved"].waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["video-save"].tap()
    }
    private func cleanupBatch(_ app: XCUIApplication) {
        app.terminate(); app.launchArguments = ["-cleaner.ui", "-cleaner.batch-cleanup"]; app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let confirmation = springboard.alerts.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Delete")).firstMatch
        if confirmation.waitForExistence(timeout: 5) { confirmation.tap() }
        XCTAssertTrue(app.staticTexts["Batch fixture ready"].waitForExistence(timeout: 20))
    }
    func testCompressionReceiptAudit() {
        continueAfterFailure = false
        let app = XCUIApplication(); defer { cleanupBatch(app) }
        app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.batch-audit"]; app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow Full Access"]
        if allow.waitForExistence(timeout: 5) { allow.tap() }
        XCTAssertTrue(app.staticTexts["Batch audit passed"].waitForExistence(timeout: 90), app.debugDescription)
    }
    func testCompressionQueue() {
        continueAfterFailure = false
        let app = XCUIApplication(); defer { cleanupBatch(app) }
        app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.batch-fixture"]; app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow Full Access"]
        if allow.waitForExistence(timeout: 5) { allow.tap() }
        XCTAssertTrue(app.tabBars.buttons["Photos"].waitForExistence(timeout: 30)); app.tabBars.buttons["Photos"].tap()
        app.buttons["Batch actions"].tap(); app.buttons["compression-batch-open"].tap()
        let prepare = app.buttons["compression-batch-prepare"]
        XCTAssertTrue(prepare.waitForExistence(timeout: 20))
        let before = XCTAttachment(screenshot: app.screenshot()); before.name = "batch-ready"; before.lifetime = .keepAlways; add(before)
        prepare.tap()
        let save = app.buttons["compression-save"]
        XCTAssertTrue(save.waitForExistence(timeout: 30))
        for _ in 0..<12 { if save.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(save.isEnabled); save.tap()
        XCTAssertTrue(prepare.waitForExistence(timeout: 30)); prepare.tap()
        XCTAssertTrue(app.buttons["Retry preparation"].waitForExistence(timeout: 20))
        app.buttons["compression-skip"].tap()
        XCTAssertEqual(app.staticTexts["compression-batch-summary"].label, "1 saved · 1 skipped · 0 unverified")
        let result = XCTAttachment(screenshot: app.screenshot()); result.name = "batch-complete"; result.lifetime = .keepAlways; add(result)
        app.buttons["compression-batch-done"].tap()
        XCTAssertTrue(app.tabBars.buttons["Photos"].waitForExistence(timeout: 10))
    }
    func testQualityChangeRequiresUpdatedPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.tour", "compression", "-cleaner.theme", "light"]
        app.launch()
        XCTAssertTrue(app.otherElements["cleaner.tour.ready"].waitForExistence(timeout: 30))
        let slider = app.sliders["compression-quality"]
        for _ in 0..<4 { if slider.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(slider.exists); slider.adjust(toNormalizedSliderPosition: 0)
        let update = app.buttons["compression-update"]
        for _ in 0..<3 { if update.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(update.isEnabled); update.tap()
        let measured = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Quality setting: 0.90")).firstMatch
        XCTAssertTrue(measured.waitForExistence(timeout: 20))
        XCTAssertFalse(update.isEnabled)
        let size = app.buttons["compression-size"]
        for _ in 0..<5 { if size.isHittable { break }; app.swipeDown() }
        XCTAssertTrue(size.exists); size.tap(); app.buttons["800 px max"].tap()
        for _ in 0..<4 { if update.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(update.isEnabled); update.tap()
        let resized = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Output: 800 × 533")).firstMatch
        XCTAssertTrue(resized.waitForExistence(timeout: 20))
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "compression-resize"; image.lifetime = .keepAlways; add(image)
    }
    func testResumeComparison() {
        continueAfterFailure = false
        let app = XCUIApplication()
        defer {
            app.terminate(); app.launchArguments = ["-cleaner.ui", "-cleaner.resume-cleanup"]; app.launch()
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let confirmation = springboard.alerts.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Delete")).firstMatch
            if confirmation.waitForExistence(timeout: 5) { confirmation.tap() }
            XCTAssertTrue(app.staticTexts["Fixture cleanup complete"].waitForExistence(timeout: 20))
        }
        app.resetAuthorizationStatus(for: .photos)
        app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.resume-fixture"]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow Full Access"]
        XCTAssertTrue(allow.waitForExistence(timeout: 15)); allow.tap()
        let saved = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Saved measurements: 1.")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 30))
        app.terminate(); app.launchArguments = ["-cleaner.ui"]; app.launch()
        XCTAssertTrue(saved.waitForExistence(timeout: 20))
        app.buttons["cleaner.scan"].tap()
        let resume = app.buttons["Continue comparison"]
        XCTAssertTrue(resume.waitForExistence(timeout: 20)); resume.tap()
        XCTAssertTrue(app.buttons["cleaner.review-groups"].waitForExistence(timeout: 30))
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "cleaner-resumed-comparison"; image.lifetime = .keepAlways; add(image)
        app.buttons["Settings"].tap()
        let clear = app.buttons["clear-scan-progress"]
        for _ in 0..<5 { if clear.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(clear.isHittable); clear.tap()
        XCTAssertTrue(app.staticTexts["Saved measurements: 0"].waitForExistence(timeout: 15))
        let settings = XCTAttachment(screenshot: app.screenshot()); settings.name = "cleaner-cleared-progress"; settings.lifetime = .keepAlways; add(settings)
        app.navigationBars.buttons.firstMatch.tap()
        let activity = app.buttons["Activity"]
        for _ in 0..<5 { if activity.isHittable { break }; app.swipeUp() }
        activity.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Compared")).firstMatch.waitForExistence(timeout: 10))
    }

    func testFileFiltersComposeAndClear() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.seed", "-cleaner.dates"]; app.launch()
        XCTAssertTrue(app.navigationBars["Cleaner"].waitForExistence(timeout: 30)); app.tabBars.buttons["Files"].tap()
        func reveal(_ item: XCUIElement) { for _ in 0..<10 { if item.isHittable { break }; app.swipeUp() }; XCTAssertTrue(item.isHittable) }
        func open() { let item = app.buttons["files-filter-open"]; for _ in 0..<10 { if item.isHittable { break }; app.swipeDown() }; reveal(item); item.tap() }
        func choose(_ field: String, _ value: String) { reveal(app.buttons[field]); app.buttons[field].tap(); app.buttons[value].tap() }
        func count(_ value: Int) { reveal(app.staticTexts["files-visible-count"]); XCTAssertEqual(app.staticTexts["files-visible-count"].label, "\(value) of 5 files") }
        count(5); open(); choose("files-extension", ".pdf"); choose("files-size", "At least 10 MB"); choose("cleaner-date-mode", "On a date")
        let editor = XCTAttachment(screenshot: app.screenshot()); editor.name = "File filter editor"; editor.lifetime = .keepAlways; add(editor)
        app.buttons["files-apply"].tap(); count(1)
        open(); choose("files-size", "Size unknown"); app.buttons["Cancel"].tap(); count(1)
        let result = XCTAttachment(screenshot: app.screenshot()); result.name = "Filtered folder"; result.lifetime = .keepAlways; add(result)
        open(); reveal(app.buttons["files-clear-draft"]); app.buttons["files-clear-draft"].tap()
        for _ in 0..<8 { if app.buttons["files-size"].isHittable { break }; app.swipeDown() }
        choose("files-size", "Size unknown"); app.buttons["files-apply"].tap(); count(1)
        let unknown = app.descendants(matching: .any)["file-file-unknown"]; reveal(unknown); XCTAssertTrue(unknown.exists)
        open(); choose("files-size", "Any size"); choose("files-extension", "No extension"); app.buttons["files-apply"].tap(); count(1)
        let empty = app.descendants(matching: .any)["file-file-empty"]; reveal(empty); XCTAssertTrue(empty.exists)
        reveal(app.buttons["files-clear"]); app.buttons["files-clear"].tap(); count(5)
        open()
        let duplicateToggle = app.switches["files-duplicates"]
        reveal(duplicateToggle)
        XCTAssertEqual(duplicateToggle.value as? String, "0")
        duplicateToggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertEqual(duplicateToggle.value as? String, "1")
        app.buttons["files-apply"].tap(); count(2)
    }

    func testDateFiltersApplyCancelAndClear() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.seed", "-cleaner.dates"]; app.launch()
        XCTAssertTrue(app.navigationBars["Cleaner"].waitForExistence(timeout: 30))
        app.tabBars.buttons["Photos"].tap()
        func open() {
            app.buttons["Sort and filter"].tap()
            app.buttons["cleaner-date-open"].tap()
            XCTAssertTrue(app.navigationBars["Date filter"].waitForExistence(timeout: 5))
        }
        func choose(_ label: String) {
            app.buttons["cleaner-date-mode"].tap(); app.buttons[label].tap()
        }
        func count(_ expected: String) {
            XCTAssertTrue(app.staticTexts["cleaner-visible-count"].waitForExistence(timeout: 5))
            XCTAssertEqual(app.staticTexts["cleaner-visible-count"].label, expected)
        }
        count("4")
        open(); choose("On this day")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Date filter editor"; shot.lifetime = .keepAlways; add(shot)
        app.buttons["cleaner-date-apply"].tap(); count("1")
        open(); choose("In a month"); app.buttons["Cancel"].tap(); count("1")
        open(); choose("In a month"); app.buttons["cleaner-date-apply"].tap(); count("1")
        open(); choose("In a year"); app.buttons["cleaner-date-apply"].tap(); count("2")
        let clear = app.buttons["cleaner-date-active"]
        for _ in 0..<8 { if clear.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(clear.isHittable)
        let result = XCTAttachment(screenshot: app.screenshot()); result.name = "Date filtered library"; result.lifetime = .keepAlways; add(result)
        clear.tap(); count("4")
        XCTAssertTrue(app.staticTexts["1 selected"].exists)
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["-cleaner.ui", "-cleaner.reset", "-cleaner.seed"]; app.launch(); return app
    }
    func testAllCleanupAreasAreReachableWithoutPermissionOnLaunch() {
        let app = launch()
        XCTAssertTrue(app.navigationBars["Cleaner"].waitForExistence(timeout: 30))
        for title in ["Photos", "Videos", "Files", "Contacts"] {
            app.tabBars.buttons[title].tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
        }
        XCTAssertTrue(app.buttons["Scan contacts"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "cleaner-contacts"; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testExactSuggestionKeepsFavoriteAndPersistsReview() {
        let app = launch()
        XCTAssertTrue(app.navigationBars["Cleaner"].waitForExistence(timeout: 30))
        app.buttons["cleaner.groups"].tap()
        app.buttons["Select suggested exact copies"].tap()
        app.buttons["cleaner.queue-selection"].tap()
        app.buttons["Review pending removal"].tap()
        XCTAssertTrue(app.staticTexts["Example copy"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Example landscape"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "cleaner-pending-review"; attachment.lifetime = .keepAlways; add(attachment)
        app.terminate(); app.launchArguments = ["-cleaner.ui", "-cleaner.seed"]; app.launch()
        XCTAssertTrue(app.buttons["cleaner.pending"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.buttons["cleaner.pending"].value as? String,"1")
    }
}
