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
        turnOn(app.switches["I have saved my recovery key"])
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
