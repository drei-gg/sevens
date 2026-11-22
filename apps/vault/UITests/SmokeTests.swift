import XCTest

@MainActor final class VaultSmokeTests: XCTestCase {
    /// A Form toggle on iOS 26 exposes its control as an inner switch; tapping the row's far edge
    /// by coordinate can miss it while the keyboard animates.
    private func turnOn(_ toggle: XCUIElement) {
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        let control = toggle.switches.firstMatch
        (control.exists ? control : toggle).tap()
        if (toggle.value as? String) != "1" { toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap() }
        let on = expectation(for: NSPredicate(format: "value == '1'"), evaluatedWith: toggle)
        XCTAssertEqual(XCTWaiter.wait(for: [on], timeout: 3), .completed)
    }
    /// Type into a secure field and confirm every character arrived: on the iOS 26 simulator, typing
    /// right after focus moves between secure fields can drop all but the first keystroke.
    private func typeSecret(_ text: String, into field: XCUIElement) {
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        for _ in 0..<3 {
            field.tap()
            let focused = expectation(for: NSPredicate(format: "hasKeyboardFocus == true"), evaluatedWith: field)
            _ = XCTWaiter.wait(for: [focused], timeout: 3)
            let current = (field.value as? String)?.count ?? 0
            if current > 0 && current != text.count { field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current)) }
            if (field.value as? String)?.count != text.count { field.typeText(text) }
            if (field.value as? String)?.count == text.count { return }
        }
        XCTFail("\(field.identifier) holds \((field.value as? String)?.count ?? -1) of \(text.count) characters")
    }
    private func replaceNumber(_ field: XCUIElement, with value: String) {
        // Keep the insertion point after the previous typed value; retapping an already focused
        // field can move it before the number and turn replacement into insertion.
        if !NSPredicate(format: "hasKeyboardFocus == true").evaluate(with: field) {
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap()
        }
        let focused = expectation(for: NSPredicate(format: "hasKeyboardFocus == true"), evaluatedWith: field)
        XCTAssertEqual(XCTWaiter.wait(for: [focused], timeout: 3), .completed)
        let count = (field.value as? String)?.count ?? 0
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: count) + value)
        XCTAssertEqual(field.value as? String, value)
    }
    func testSelectedRecognitionQueueReviewsSkipsAndLocks() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-vault.ui", "-vault.reset", "-vault.batch"]
        app.launch()
        XCTAssertTrue(app.buttons["Create a vault"].waitForExistence(timeout: 20))
        app.buttons["Create a vault"].tap()
        let name = app.textFields["vault.newName"]; XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("Personal")
        let pass = app.secureTextFields["vault.newPassword"]; typeSecret("several private testing words", into: pass)
        let confirm = app.secureTextFields["vault.repeatPassword"]; typeSecret("several private testing words", into: confirm)
        app.swipeUp()
        turnOn(app.switches["vault.acknowledge"])
        XCTAssertTrue(app.buttons["vault.create"].wait(for: \.isEnabled, toEqual: true, timeout: 5))
        app.buttons["vault.create"].tap()
        let key = app.staticTexts["vault.recoveryKey"]
        XCTAssertTrue(key.waitForExistence(timeout: 20))
        let recovery = key.label
        XCTAssertGreaterThan(recovery.count, 30)
        turnOn(app.switches["vault.recoverySaved"])
        app.buttons["Continue to vault"].tap()
        XCTAssertTrue(app.buttons["Import sample files"].waitForExistence(timeout: 10))
        app.buttons["Import sample files"].tap()
        let select = app.buttons["Select"]; XCTAssertTrue(select.waitForExistence(timeout: 20)); select.tap()
        let all = app.buttons["Select all shown"]
        for _ in 0..<10 { if all.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(all.isHittable); all.tap()
        let open = app.buttons["ocr-batch-open"]
        for _ in 0..<5 { if open.isHittable { break }; app.swipeUp() }
        open.tap()
        let start = app.buttons["ocr-batch-start"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        for _ in 0..<5 { if start.isHittable { break }; app.swipeUp() }
        start.tap()
        let text = app.textViews["ocr-batch-text"]
        XCTAssertTrue(text.waitForExistence(timeout: 30))
        XCTAssertTrue((text.value as? String ?? "").contains("7429"))
        capture("Batch image review", app: app)
        app.buttons["ocr-batch-save"].tap()
        let failure = app.staticTexts["ocr-batch-error"]
        XCTAssertTrue(failure.waitForExistence(timeout: 30))
        app.buttons["Retry this file"].tap()
        XCTAssertTrue(failure.waitForExistence(timeout: 30))
        app.buttons["ocr-batch-skip"].tap()
        XCTAssertTrue(text.waitForExistence(timeout: 30))
        XCTAssertTrue((text.value as? String ?? "").contains("BATCH FIRST PAGE 7429"))
        XCTAssertTrue((text.value as? String ?? "").contains("BATCH SECOND PAGE 8250"))
        XCTAssertEqual(app.staticTexts["ocr-batch-counts"].label, "1 saved · 1 skipped")
        capture("Batch PDF review", app: app)
        app.buttons["ocr-lock"].tap()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 15)); XCTAssertFalse(text.exists)
        typeSecret("several private testing words", into: app.secureTextFields["vault.credential"])
        app.buttons["vault.unlock"].tap()
        let note = app.staticTexts["Weekend text.txt"]
        XCTAssertTrue(note.waitForExistence(timeout: 20))
        for _ in 0..<8 { if note.isHittable { break }; app.swipeUp() }
        note.tap()
        XCTAssertTrue(app.textViews["Note text"].waitForExistence(timeout: 10))
        XCTAssertTrue((app.textViews["Note text"].value as? String ?? "").contains("PRIVATE ARCHIVE 7429"))
    }
    func testRecognizedTextReviewSavesEncryptedNoteAndLocks() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-vault.ui", "-vault.reset"]
        app.launch()
        XCTAssertTrue(app.buttons["Create a vault"].waitForExistence(timeout: 20))
        app.buttons["Create a vault"].tap()
        let name = app.textFields["vault.newName"]; XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("Personal")
        let pass = app.secureTextFields["vault.newPassword"]; typeSecret("several private testing words", into: pass)
        let confirm = app.secureTextFields["vault.repeatPassword"]; typeSecret("several private testing words", into: confirm)
        app.swipeUp()
        turnOn(app.switches["vault.acknowledge"])
        XCTAssertTrue(app.buttons["vault.create"].wait(for: \.isEnabled, toEqual: true, timeout: 5))
        app.buttons["vault.create"].tap()
        let key = app.staticTexts["vault.recoveryKey"]
        XCTAssertTrue(key.waitForExistence(timeout: 20))
        let recovery = key.label
        XCTAssertGreaterThan(recovery.count, 30)
        turnOn(app.switches["vault.recoverySaved"])
        app.buttons["Continue to vault"].tap()
        XCTAssertTrue(app.buttons["Import sample files"].waitForExistence(timeout: 10))
        app.buttons["Import sample files"].tap()
        XCTAssertTrue(app.buttons.matching(identifier: "vault.item").firstMatch.waitForExistence(timeout: 20))
        let original = app.staticTexts["Weekend.png"]
        XCTAssertTrue(original.waitForExistence(timeout: 10))
        for _ in 0..<5 { if original.isHittable && original.frame.maxY < app.frame.height * 0.75 { break }; app.swipeUp() }
        original.tap()
        let recognize = app.buttons["Recognize text"]
        for _ in 0..<5 { if recognize.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(recognize.exists); recognize.tap()
        let text = app.textViews["ocr-text"]
        XCTAssertTrue(text.waitForExistence(timeout: 30))
        XCTAssertTrue((text.value as? String ?? "").contains("PRIVATE ARCHIVE 7429"))
        capture("Recognized text review", app: app)
        let noteName = app.textFields["ocr-name"]
        noteName.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap(); noteName.typeText(" reviewed")
        app.buttons["Close"].tap()
        XCTAssertTrue(app.buttons["Discard"].waitForExistence(timeout: 5))
        app.buttons["Keep editing"].tap()
        app.buttons["ocr-save"].tap()
        XCTAssertTrue(text.wait(for: \.exists, toEqual: false, timeout: 15))
        let note = app.staticTexts["Weekend text reviewed.txt"]
        XCTAssertTrue(note.waitForExistence(timeout: 10))
        XCTAssertTrue(original.waitForExistence(timeout: 10))
        for _ in 0..<5 { if original.isHittable && original.frame.maxY < app.frame.height * 0.75 { break }; app.swipeUp() }
        original.tap()
        XCTAssertTrue(recognize.waitForExistence(timeout: 10))
        for _ in 0..<5 { if recognize.isHittable { break }; app.swipeUp() }
        recognize.tap()
        XCTAssertTrue(text.waitForExistence(timeout: 30))
        app.buttons["ocr-lock"].tap()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 10))
        XCTAssertFalse(text.exists)
        let unlock = app.secureTextFields["vault.credential"]
        typeSecret("several private testing words", into: unlock)
        app.buttons["vault.unlock"].tap()
        XCTAssertTrue(note.waitForExistence(timeout: 20)); note.tap()
        XCTAssertTrue(app.textViews["Note text"].waitForExistence(timeout: 10))
        XCTAssertTrue((app.textViews["Note text"].value as? String ?? "").contains("PRIVATE ARCHIVE 7429"))
    }
    func testPdfRangeRecognitionSavesEncryptedNoteAndLocks() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-vault.ui", "-vault.reset", "-vault.pdf"]
        app.launch()
        XCTAssertTrue(app.buttons["Create a vault"].waitForExistence(timeout: 20))
        app.buttons["Create a vault"].tap()
        let name = app.textFields["vault.newName"]; XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("Personal")
        let pass = app.secureTextFields["vault.newPassword"]; typeSecret("several private testing words", into: pass)
        let confirm = app.secureTextFields["vault.repeatPassword"]; typeSecret("several private testing words", into: confirm)
        app.swipeUp()
        turnOn(app.switches["vault.acknowledge"])
        XCTAssertTrue(app.buttons["vault.create"].wait(for: \.isEnabled, toEqual: true, timeout: 5))
        app.buttons["vault.create"].tap()
        let key = app.staticTexts["vault.recoveryKey"]
        XCTAssertTrue(key.waitForExistence(timeout: 20))
        let recovery = key.label
        XCTAssertGreaterThan(recovery.count, 30)
        turnOn(app.switches["vault.recoverySaved"])
        app.buttons["Continue to vault"].tap()
        XCTAssertTrue(app.buttons["Import sample files"].waitForExistence(timeout: 10))
        app.buttons["Import sample files"].tap()
        XCTAssertTrue(app.buttons.matching(identifier: "vault.item").firstMatch.waitForExistence(timeout: 20))
        let original = app.staticTexts["Selected pages.pdf"]
        XCTAssertTrue(original.waitForExistence(timeout: 10))
        for _ in 0..<5 { if original.isHittable && original.frame.maxY < app.frame.height * 0.75 { break }; app.swipeUp() }
        original.tap()
        let recognize = app.buttons["Recognize text"]
        for _ in 0..<5 { if recognize.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(recognize.exists); recognize.tap()
        let first = app.textFields["ocr-first"], last = app.textFields["ocr-last"]
        XCTAssertTrue(first.waitForExistence(timeout: 20))
        replaceNumber(first, with: "0")
        XCTAssertFalse(app.buttons["ocr-recognize"].isEnabled)
        replaceNumber(first, with: "2")
        replaceNumber(last, with: "2")
        capture("PDF page range", app: app)
        app.buttons["ocr-recognize"].tap()
        let text = app.textViews["ocr-text"]
        XCTAssertTrue(text.waitForExistence(timeout: 30))
        XCTAssertTrue((text.value as? String ?? "").contains("PRIVATE ARCHIVE 7429"))
        XCTAssertTrue((text.value as? String ?? "").contains("Page 2"))
        XCTAssertFalse((text.value as? String ?? "").contains("OUTSIDE"))
        capture("PDF recognized text review", app: app)
        let noteName = app.textFields["ocr-name"]
        noteName.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap(); noteName.typeText(" reviewed")
        app.buttons["Close"].tap()
        XCTAssertTrue(app.buttons["Discard"].waitForExistence(timeout: 5))
        app.buttons["Keep editing"].tap()
        app.buttons["ocr-save"].tap()
        XCTAssertTrue(text.wait(for: \.exists, toEqual: false, timeout: 15))
        let note = app.staticTexts["Selected pages text reviewed.txt"]
        XCTAssertTrue(note.waitForExistence(timeout: 10))
        XCTAssertTrue(original.waitForExistence(timeout: 10))
        for _ in 0..<5 { if original.isHittable && original.frame.maxY < app.frame.height * 0.75 { break }; app.swipeUp() }
        original.tap()
        XCTAssertTrue(recognize.waitForExistence(timeout: 10))
        for _ in 0..<5 { if recognize.isHittable { break }; app.swipeUp() }
        recognize.tap()
        XCTAssertTrue(first.waitForExistence(timeout: 20))
        app.buttons["ocr-recognize"].tap()
        XCTAssertTrue(text.waitForExistence(timeout: 30))
        app.buttons["ocr-lock"].tap()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 10))
        XCTAssertFalse(text.exists)
        let unlock = app.secureTextFields["vault.credential"]
        typeSecret("several private testing words", into: unlock)
        app.buttons["vault.unlock"].tap()
        XCTAssertTrue(note.waitForExistence(timeout: 20)); note.tap()
        XCTAssertTrue(app.textViews["Note text"].waitForExistence(timeout: 10))
        XCTAssertTrue((app.textViews["Note text"].value as? String ?? "").contains("PRIVATE ARCHIVE 7429"))
    }
    func testNoteContentSearchClearsOnLock() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-vault.ui", "-vault.reset"]
        app.launch()
        XCTAssertTrue(app.buttons["Create a vault"].waitForExistence(timeout: 20))
        app.buttons["Create a vault"].tap()
        let name = app.textFields["vault.newName"]; XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("Personal")
        let pass = app.secureTextFields["vault.newPassword"]; typeSecret("several private testing words", into: pass)
        let confirm = app.secureTextFields["vault.repeatPassword"]; typeSecret("several private testing words", into: confirm)
        app.swipeUp()
        turnOn(app.switches["vault.acknowledge"])
        XCTAssertTrue(app.buttons["vault.create"].wait(for: \.isEnabled, toEqual: true, timeout: 5))
        app.buttons["vault.create"].tap()
        let key = app.staticTexts["vault.recoveryKey"]
        XCTAssertTrue(key.waitForExistence(timeout: 20))
        let recovery = key.label
        XCTAssertGreaterThan(recovery.count, 30)
        turnOn(app.switches["vault.recoverySaved"])
        app.buttons["Continue to vault"].tap()
        XCTAssertTrue(app.buttons["Import sample files"].waitForExistence(timeout: 10))
        app.buttons["Import sample files"].tap()
        XCTAssertTrue(app.buttons.matching(identifier: "vault.item").firstMatch.waitForExistence(timeout: 20))
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("Spare charger")
        XCTAssertTrue(app.staticTexts["No matching files"].waitForExistence(timeout: 5))
        turnOn(app.switches["vault.searchNotes"])
        let note = app.staticTexts["Travel checklist.txt"]
        XCTAssertTrue(note.waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["vault.searchCoverage"].label, "Searched 1 note")
        capture("Note content search", app: app)
        search.typeText("\n")
        let list = app.collectionViews.firstMatch
        XCTAssertTrue(list.exists)
        for _ in 0..<6 { if note.isHittable { break }; list.swipeUp() }
        capture("Search results viewport", app: app)
        XCTAssertTrue(note.isHittable, app.debugDescription)
        capture("Matching note", app: app)
        // Native active search replaces the navigation toolbar. Background with the query still
        // present to exercise the same lock boundary without cancelling search first.
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 10))
        XCTAssertFalse(note.exists)
        typeSecret("several private testing words", into: app.secureTextFields["vault.credential"])
        app.buttons["vault.unlock"].tap()
        XCTAssertTrue(note.waitForExistence(timeout: 20))
        XCTAssertEqual(app.switches["vault.searchNotes"].value as? String, "0")
        XCTAssertFalse(app.staticTexts["vault.searchCoverage"].exists)
        XCTAssertNotEqual(app.searchFields.firstMatch.value as? String, "Spare charger")
    }
    func testCreateNoteImportLockAndRecover() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-vault.ui", "-vault.reset"]
        app.launch()
        XCTAssertTrue(app.buttons["Create a vault"].waitForExistence(timeout: 20))
        app.buttons["Create a vault"].tap()
        let name = app.textFields["vault.newName"]; XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("Personal")
        let pass = app.secureTextFields["vault.newPassword"]; typeSecret("several private testing words", into: pass)
        let confirm = app.secureTextFields["vault.repeatPassword"]; typeSecret("several private testing words", into: confirm)
        app.swipeUp()
        turnOn(app.switches["vault.acknowledge"])
        XCTAssertTrue(app.buttons["vault.create"].wait(for: \.isEnabled, toEqual: true, timeout: 5))
        app.buttons["vault.create"].tap()
        let key = app.staticTexts["vault.recoveryKey"]
        XCTAssertTrue(key.waitForExistence(timeout: 20))
        let recovery = key.label
        XCTAssertGreaterThan(recovery.count, 30)
        turnOn(app.switches["vault.recoverySaved"])
        app.buttons["Continue to vault"].tap()
        XCTAssertTrue(app.buttons["Import sample files"].waitForExistence(timeout: 10))
        app.buttons["Import sample files"].tap()
        XCTAssertTrue(app.buttons.matching(identifier: "vault.item").firstMatch.waitForExistence(timeout: 20))
        capture("Library", app: app)
        app.buttons["Enable test automatic backup"].tap()
        XCTAssertTrue(app.staticTexts["vault.testBackupCurrent"].waitForExistence(timeout: 30))
        app.buttons["Note"].tap()
        let note = app.textFields["vault.noteName"]; XCTAssertTrue(note.waitForExistence(timeout: 5)); note.tap(); note.typeText("Recovery plan")
        let editor = app.textViews["vault.noteText"]; editor.tap(); editor.typeText("Keep a separate encrypted backup.")
        app.swipeUp(); app.buttons["vault.saveNote"].tap()
        XCTAssertTrue(app.staticTexts["Recovery plan.txt"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["vault.testBackupCurrent"].waitForExistence(timeout: 30))
        app.buttons["Verify test automatic backup"].tap()
        XCTAssertTrue(app.staticTexts["Verified automatic backup: 3 files"].waitForExistence(timeout: 20))
        app.buttons["vault.lock"].tap()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Recovery plan.txt"].exists)
        capture("Locked", app: app)
        let credential = app.secureTextFields["vault.credential"]; credential.tap(); credential.typeText("definitely incorrect password")
        app.buttons["vault.unlock"].tap()
        XCTAssertTrue(app.alerts["Could not complete"].waitForExistence(timeout: 15)); app.alerts.buttons["OK"].tap()
        turnOn(app.switches["Use a recovery key"]); credential.tap(); credential.typeText(recovery); app.buttons["vault.unlock"].tap()
        XCTAssertTrue(app.staticTexts["Recovery plan.txt"].waitForExistence(timeout: 15))
        // An actual process restart must require credentials and preserve encrypted data.
        app.terminate(); app.launchArguments = ["-vault.ui"]; app.launch()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 15))
        app.secureTextFields["vault.credential"].tap(); app.secureTextFields["vault.credential"].typeText("several private testing words"); app.buttons["vault.unlock"].tap()
        XCTAssertTrue(app.staticTexts["Recovery plan.txt"].waitForExistence(timeout: 15))
        app.buttons["Expire trial for testing"].tap()
        app.buttons["Select"].tap(); app.swipeUp(); app.buttons["Select all shown"].tap(); app.buttons["Export selected originals"].tap()
        XCTAssertTrue(app.cells["Save to Files"].waitForExistence(timeout: 15))
        capture("Originals export", app: app)
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["Recovery plan.txt"].exists)
    }
    func testPINAndPatternOpenDifferentVaults() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["-vault.ui", "-vault.reset", "-vault.routes"]; app.launch()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 20))
        app.segmentedControls.buttons["PIN"].tap()
        app.secureTextFields["vault.credential"].tap(); app.secureTextFields["vault.credential"].typeText("00123456")
        app.buttons["vault.unlock"].tap()
        XCTAssertTrue(app.navigationBars["PIN library"].waitForExistence(timeout: 15))
        app.buttons["vault.lock"].tap()
        XCTAssertTrue(app.buttons["vault.unlock"].waitForExistence(timeout: 10))
        app.segmentedControls.buttons["Pattern"].tap()
        app.buttons["vault.credential.clear"].tap()
        app.buttons["vault.credential.dot.0"].press(forDuration: 0.1, thenDragTo: app.buttons["vault.credential.dot.20"])
        for dot in [21, 22, 23, 24] { app.buttons["vault.credential.dot.\(dot)"].tap() }
        capture("Pattern entry", app: app)
        app.buttons["vault.unlock"].tap()
        XCTAssertTrue(app.navigationBars["Pattern library"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.navigationBars["PIN library"].exists)
    }
    private func capture(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
