import XCTest
@MainActor final class SmokeTests: XCTestCase {
    func testAnnotationsUndoSaveReopenAndExport() {
        let app = launch()
        let document = app.buttons.matching(identifier: "scanner.document").firstMatch
        XCTAssertTrue(document.waitForExistence(timeout: 30)); document.tap()
        let page = app.buttons["scanner.page.1"]; reveal(page, in: app); page.tap()
        let annotate = app.buttons["page.annotate"]; reveal(annotate, in: app); annotate.tap()
        let add = app.buttons["annotations-exact"]
        XCTAssertTrue(add.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: add); waitForExpectations(timeout: 10)
        let canvas = app.descendants(matching: .any)["annotations-canvas"].firstMatch
        XCTAssertTrue(canvas.exists)
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.4)).press(forDuration: 0.1, thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.6)))
        XCTAssertEqual(app.staticTexts["annotations-count"].label, "1 of 100 marks")
        reveal(app.buttons["annotations-undo"], in: app); app.buttons["annotations-undo"].tap()
        XCTAssertEqual(app.staticTexts["annotations-count"].label, "0 of 100 marks")
        add.tap(); XCTAssertTrue(app.buttons["annotations-add"].waitForExistence(timeout: 5)); app.buttons["annotations-add"].tap()
        XCTAssertEqual(app.staticTexts["annotations-count"].label, "1 of 100 marks")
        app.buttons["annotations-undo"].tap(); app.buttons["annotations-redo"].tap()
        XCTAssertEqual(app.staticTexts["annotations-count"].label, "1 of 100 marks")
        reveal(app.segmentedControls["ink-tool"], in: app, down: true)
        app.segmentedControls["ink-tool"].buttons["Ink"].tap()
        let inkCanvas = app.descendants(matching: .any)["ink-canvas"].firstMatch
        revealInkControl(inkCanvas, in: app)
        let beforeInk = XCTAttachment(screenshot: app.screenshot()); beforeInk.name = "Before native ink"; beforeInk.lifetime = .keepAlways; self.add(beforeInk)
        inkCanvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.55)).press(forDuration: 0.1, thenDragTo: inkCanvas.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.7)))
        let afterInk = XCTAttachment(screenshot: app.screenshot()); afterInk.name = "After native ink"; afterInk.lifetime = .keepAlways; self.add(afterInk)
        expectation(for: NSPredicate(format: "label == %@", "1 of 100 ink strokes"), evaluatedWith: app.staticTexts["ink-count"]); waitForExpectations(timeout: 10)
        revealInkControl(app.buttons["annotations-undo"], in: app); app.buttons["annotations-undo"].tap()
        XCTAssertEqual(app.staticTexts["ink-count"].label, "0 of 100 ink strokes")
        app.buttons["annotations-redo"].tap()
        XCTAssertEqual(app.staticTexts["ink-count"].label, "1 of 100 ink strokes")
        let editor = XCTAttachment(screenshot: app.screenshot()); editor.name = "Annotations editor"; editor.lifetime = .keepAlways; self.add(editor)
        app.buttons["annotations-save"].tap()
        XCTAssertTrue(annotate.waitForExistence(timeout: 10))
        app.terminate(); app.launchArguments = ["-scanner.ui"]; app.launch()
        XCTAssertTrue(document.waitForExistence(timeout: 15)); document.tap(); reveal(page, in: app); page.tap()
        reveal(annotate, in: app); annotate.tap()
        XCTAssertEqual(app.staticTexts["annotations-count"].label, "1 of 100 marks")
        XCTAssertEqual(app.staticTexts["ink-count"].label, "1 of 100 ink strokes")
        app.buttons["Close"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let export = app.buttons["scanner.export"]; reveal(export, in: app, down: true); export.tap()
        let prepare = app.buttons["export.prepare"]; reveal(prepare, in: app); prepare.tap()
        let preview = app.buttons["export.preview"]; XCTAssertTrue(preview.waitForExistence(timeout: 30)); reveal(preview, in: app); preview.tap()
        XCTAssertTrue(app.navigationBars["PDF preview"].waitForExistence(timeout: 10))
        let pdf = XCTAttachment(screenshot: app.screenshot()); pdf.name = "Saved annotation PDF preview"; pdf.lifetime = .keepAlways; self.add(pdf)
    }
    func testCropBoundsPersistAndNativeHandlesOpen() {
        let app = launch()
        let document = app.buttons.matching(identifier: "scanner.document").firstMatch
        XCTAssertTrue(document.waitForExistence(timeout: 30)); document.tap()
        let page = app.buttons["scanner.page.1"]; reveal(page, in: app); page.tap()
        let crop = app.buttons["page.crop"]; reveal(crop, in: app); crop.tap()
        let handles = app.buttons["page.cropHandles"]
        XCTAssertTrue(handles.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: handles); waitForExpectations(timeout: 10)
        handles.tap()
        let useBounds = app.buttons["page.cropUseBounds"]
        XCTAssertTrue(useBounds.waitForExistence(timeout: 10))
        let handlesImage = XCTAttachment(screenshot: app.screenshot()); handlesImage.name = "native-crop-handles"; handlesImage.lifetime = .keepAlways; add(handlesImage)
        useBounds.tap()
        let right = app.textFields["page.cropRight"]; reveal(right, in: app); right.tap()
        right.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (right.value as? String ?? "").count) + "80")
        let bottom = app.textFields["page.cropBottom"]; reveal(bottom, in: app); bottom.tap()
        bottom.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (bottom.value as? String ?? "").count) + "60")
        app.buttons["page.useCrop"].tap()
        app.buttons["page.save"].tap()
        XCTAssertTrue(page.waitForExistence(timeout: 10))
        app.terminate(); app.launchArguments = ["-scanner.ui"]; app.launch()
        XCTAssertTrue(document.waitForExistence(timeout: 15)); document.tap(); reveal(page, in: app); page.tap()
        reveal(crop, in: app); crop.tap()
        XCTAssertTrue(right.waitForExistence(timeout: 5)); XCTAssertEqual(right.value as? String, "80.00")
        XCTAssertEqual(bottom.value as? String, "60.00")
        XCTAssertTrue(app.images["page.imageReady"].firstMatch.waitForExistence(timeout: 10))
        let savedImage = XCTAttachment(screenshot: app.screenshot()); savedImage.name = "crop-bounds-persisted"; savedImage.lifetime = .keepAlways; add(savedImage)
    }
    func testSavedTextCorrectionSurvivesRelaunch() {
        let app = launch()
        let document = app.buttons.matching(identifier: "scanner.document").firstMatch
        XCTAssertTrue(document.waitForExistence(timeout: 30)); document.tap()
        let page = app.buttons["scanner.page.1"]
        reveal(page, in: app); page.tap()
        let edit = app.buttons["page.editText"]
        reveal(edit, in: app); edit.tap()
        let text = app.textViews["page.correctedText"]
        XCTAssertTrue(text.waitForExistence(timeout: 5)); text.tap(); text.typeText("CORRECTED TEXT QA ")
        XCTAssertTrue((text.value as? String ?? "").contains("CORRECTED TEXT QA"))
        app.buttons["page.saveText"].tap()
        XCTAssertTrue(text.wait(for: \.exists, toEqual: false, timeout: 10))
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        app.terminate(); app.launchArguments = ["-scanner.ui"]; app.launch()
        XCTAssertTrue(document.waitForExistence(timeout: 15)); document.tap()
        reveal(page, in: app); page.tap(); reveal(edit, in: app); edit.tap()
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        XCTAssertTrue((text.value as? String ?? "").contains("CORRECTED TEXT QA"))
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "text-correction"; image.lifetime = .keepAlways; add(image)
    }
    private func launch(audit: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-scanner.ui", "-scanner.reset", "-scanner.seed"] + (audit ? ["-scanner.audit"] : [])
        app.launch(); return app
    }
    /// Scroll beside the fitted native canvas. A swipe through its center correctly draws ink.
    private func revealInkControl(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 {
            if element.exists && element.isHittable && element.frame.minY >= 130 && element.frame.maxY <= app.frame.height - 50 { return }
            let down = element.exists && element.frame.minY < 130
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: down ? 0.4 : 0.75))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: down ? 0.7 : 0.45))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, down: Bool = false) {
        for _ in 0..<7 {
            if element.exists && element.isHittable {
                // Clear the floating action bar and bottom search field, which sit over the last rows.
                if !down, element.frame.maxY > app.frame.height * 0.72 { app.swipeUp() }
                return
            }
            // The library and document are scroll views now, sheets are lists: swipe the window.
            if down { app.swipeDown() } else { app.swipeUp() }
        }
        XCTAssertTrue(element.exists && element.isHittable, "Could not reveal \(element)")
    }
    func testBackupOffersFilesExport() throws {
        let app = launch()
        XCTAssertTrue(app.buttons.matching(identifier: "scanner.document").firstMatch.waitForExistence(timeout: 30))
        let backup = app.buttons["Backup & restore"]
        reveal(backup, in: app); backup.tap()
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
        reveal(title, in: app); title.tap()
        title.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (title.value as? String ?? "").count) + "Receipt test\n")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] 'Coffee'")).firstMatch.waitForExistence(timeout: 10))
        reveal(app.buttons["scanner.preview"], in: app, down: true); app.buttons["scanner.preview"].tap()
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
        XCTAssertTrue(app.buttons["scanner.export"].waitForExistence(timeout: 5)); app.buttons["scanner.export"].tap()
        let pageTwo = app.buttons["export.page.2"]
        XCTAssertTrue(pageTwo.waitForExistence(timeout: 5))
        XCTAssertTrue(pageTwo.isSelected)
        pageTwo.tap()
        XCTAssertFalse(pageTwo.isSelected)
        reveal(app.buttons["export.prepare"], in: app); app.buttons["export.prepare"].tap()
        reveal(app.buttons["export.share"], in: app)
        XCTAssertTrue(app.staticTexts["export.size"].exists)
        reveal(app.segmentedControls.buttons["Smaller file"], in: app, down: true)
        app.segmentedControls.buttons["Smaller file"].tap()
        reveal(app.buttons["export.prepare"], in: app); app.buttons["export.prepare"].tap()
        reveal(app.buttons["export.share"], in: app)
        reveal(app.buttons["export.preview"], in: app); app.buttons["export.preview"].tap()
        XCTAssertTrue(app.navigationBars["PDF preview"].waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "scanner-selected-pdf"; screenshot.lifetime = .keepAlways; add(screenshot)
        app.navigationBars["PDF preview"].buttons["Done"].tap(); app.navigationBars["Export PDF"].buttons["Done"].tap()
        reveal(app.buttons["Delete document"], in: app); app.buttons["Delete document"].tap()
        app.buttons["Move to Recently Deleted"].tap()
        XCTAssertTrue(app.navigationBars["Documents"].waitForExistence(timeout: 5))
        reveal(app.buttons["Recently Deleted"], in: app); app.buttons["Recently Deleted"].tap()
        XCTAssertTrue(app.buttons["trash.restore"].waitForExistence(timeout: 5)); app.buttons["trash.restore"].tap()
        XCTAssertTrue(app.staticTexts["Nothing in Recently Deleted"].waitForExistence(timeout: 5))
        app.terminate(); app.launchArguments = ["-scanner.ui"]; app.launch()
        XCTAssertTrue(row.waitForExistence(timeout: 15))
    }
    func testZZPageAdjustmentsAndProcessingAudit() throws {
        let app = launch(audit: true)
        let audit = app.buttons["Run processing audit"]
        XCTAssertTrue(audit.waitForExistence(timeout: 15)); reveal(audit, in: app)
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: audit)
        waitForExpectations(timeout: 30); audit.tap()
        let result = app.staticTexts["audit.result"]
        expectation(for: NSPredicate(format: "label BEGINSWITH 'PASS:' OR label BEGINSWITH 'FAIL:'"), evaluatedWith: result)
        waitForExpectations(timeout: 180)
        XCTAssertTrue(result.label.hasPrefix("PASS:"), result.label)
        let row = app.buttons.matching(identifier: "scanner.document").firstMatch
        reveal(row, in: app); row.tap()
        reveal(app.buttons["scanner.page.1"], in: app, down: true); app.buttons["scanner.page.1"].tap()
        XCTAssertTrue(app.buttons["page.rotate"].waitForExistence(timeout: 10)); app.buttons["page.rotate"].tap()
        let grayscale = app.switches["page.grayscale"]
        reveal(grayscale, in: app)
        grayscale.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertEqual(grayscale.value as? String, "1")
        app.buttons["page.save"].tap()
        XCTAssertTrue(app.textFields["scanner.title"].waitForExistence(timeout: 10))
        app.terminate(); app.launchArguments = ["-scanner.ui"]; app.launch()
        XCTAssertTrue(row.waitForExistence(timeout: 15)); reveal(row, in: app); row.tap()
        XCTAssertTrue(app.buttons["scanner.page.1"].waitForExistence(timeout: 10)); app.buttons["scanner.page.1"].tap()
        XCTAssertTrue(grayscale.waitForExistence(timeout: 10)); XCTAssertEqual(grayscale.value as? String, "1")
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "scanner-page-editor"; screenshot.lifetime = .keepAlways; add(screenshot)
    }
}
