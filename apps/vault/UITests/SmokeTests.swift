import XCTest

@MainActor final class VaultSmokeTests: XCTestCase {
    func testCreateNoteImportLockAndRecover() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-vault.ui", "-vault.reset"]
        app.launch()
        XCTAssertTrue(app.buttons["Create a vault"].waitForExistence(timeout: 20))
        app.buttons["Create a vault"].tap()
        let name = app.textFields["vault.newName"]; XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("Personal")
        let pass = app.secureTextFields["vault.newPassword"]; pass.tap(); pass.typeText("several private testing words")
        let confirm = app.secureTextFields["vault.repeatPassword"]; confirm.tap(); confirm.typeText("several private testing words")
        app.swipeUp()
        app.switches["vault.acknowledge"].coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["vault.create"].isEnabled)
        app.buttons["vault.create"].tap()
        let key = app.staticTexts["vault.recoveryKey"]
        XCTAssertTrue(key.waitForExistence(timeout: 20))
        let recovery = key.label
        XCTAssertGreaterThan(recovery.count, 30)
        app.switches["I have saved my recovery key"].coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
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
        app.switches["Use a recovery key"].coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap(); credential.tap(); credential.typeText(recovery); app.buttons["vault.unlock"].tap()
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
