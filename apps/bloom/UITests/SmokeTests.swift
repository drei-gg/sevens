import XCTest

final class SmokeTests: XCTestCase {
  @MainActor func testHealthSharingRequiresConfirmationAndCanBeCancelled() throws {
    let app = XCUIApplication()
    app.launchArguments = ["-bloom.ui", "-bloom.reset", "-bloom.seed"]
    app.launch()
    XCTAssertTrue(app.buttons["logToday"].waitForExistence(timeout: 20))
    app.tabBars.buttons["Settings"].tap()
    app.buttons["Apple Health"].tap()
    XCTAssertTrue(app.buttons["Read Health flow for review"].waitForExistence(timeout: 5))
    app.buttons["Share journal flow to Health"].tap()
    XCTAssertTrue(app.buttons["Share flow"].waitForExistence(timeout: 5))
    capture("Health sharing consent", app: app)
    // iOS 26 presents a dismissible popover here, without a separate Cancel row.
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.75)).tap()
    XCTAssertFalse(app.buttons["Share flow"].exists)
    XCTAssertTrue(app.buttons["Read Health flow for review"].exists)
    XCTAssertFalse(app.buttons["Import reviewed days"].exists)
  }
  @MainActor private func capture(_ name: String, app: XCUIApplication) {
    let shot = XCTAttachment(screenshot: app.screenshot())
    shot.name = name
    shot.lifetime = .keepAlways
    add(shot)
  }
  @MainActor func testJournalPlanAndNavigation() throws {
    let app = XCUIApplication()
    app.launchArguments = ["-bloom.ui", "-bloom.reset", "-bloom.seed", "-bloom.light"]
    app.launch()
    XCTAssertTrue(app.buttons["logToday"].waitForExistence(timeout: 20))
    capture("Today light", app: app)
    app.buttons["logToday"].tap()
    XCTAssertTrue(app.navigationBars["Daily journal"].waitForExistence(timeout: 5))
    app.buttons["Cancel"].tap()
    app.tabBars.buttons["Care"].tap()
    XCTAssertTrue(app.buttons["addPlan"].waitForExistence(timeout: 5))
    app.buttons["addPlan"].tap()
    let name = app.textFields["planName"]
    XCTAssertTrue(name.waitForExistence(timeout: 5))
    name.tap()
    name.typeText("Evening pill")
    app.buttons["saveRecord"].tap()
    XCTAssertTrue(
      app.buttons.containing(.staticText, identifier: "Evening pill").firstMatch.waitForExistence(
        timeout: 10))
    app.tabBars.buttons["Calendar"].tap()
    capture("Calendar", app: app)
    app.tabBars.buttons["Insights"].tap()
    XCTAssertTrue(app.navigationBars["Insights"].waitForExistence(timeout: 5))
    capture("Insights", app: app)
    app.tabBars.buttons["Settings"].tap()
    XCTAssertTrue(app.buttons["Tracking mode and privacy"].waitForExistence(timeout: 5))
    app.terminate()
    app.launchArguments = ["-bloom.ui", "-bloom.dark"]
    app.launch()
    capture("Today dark", app: app)
    app.tabBars.buttons["Care"].tap()
    XCTAssertTrue(
      app.buttons.containing(.staticText, identifier: "Evening pill").firstMatch.waitForExistence(
        timeout: 10))
  }
  @MainActor func testExpiredCanReadAndCorrect() throws {
    let app = XCUIApplication()
    app.launchArguments = ["-bloom.ui", "-bloom.reset", "-bloom.seed", "-bloom.expired"]
    app.launch()
    XCTAssertTrue(app.buttons["logToday"].waitForExistence(timeout: 20))
    app.buttons["logToday"].tap()
    app.buttons["saveRecord"].tap()
    XCTAssertTrue(app.buttons["logToday"].waitForExistence(timeout: 10))
    app.tabBars.buttons["Care"].tap()
    app.buttons["addPlan"].tap()
    let name = app.textFields["planName"]
    name.tap()
    name.typeText("New plan blocked")
    app.buttons["saveRecord"].tap()
    XCTAssertTrue(app.alerts["Could not save"].waitForExistence(timeout: 10))
  }

  @MainActor func testReportsBackupsAndPregnancy() throws {
    let app = XCUIApplication()
    app.launchArguments = ["-bloom.ui", "-bloom.reset", "-bloom.seed"]
    app.launch()
    XCTAssertTrue(app.buttons["logToday"].waitForExistence(timeout: 20))
    app.tabBars.buttons["Settings"].tap()
    app.buttons["Reports and selected sharing"].tap()
    XCTAssertTrue(app.buttons["Build report"].waitForExistence(timeout: 5))
    app.buttons["Build report"].tap()
    let report = app.staticTexts.matching(
      NSPredicate(format: "label BEGINSWITH %@", "Cycle & Health Report")
    ).firstMatch
    XCTAssertTrue(report.waitForExistence(timeout: 10))
    XCTAssertFalse(report.label.contains("A quieter day helped."))
    capture("Report preview", app: app)
    app.navigationBars.buttons.firstMatch.tap()
    app.buttons["Export, backup and import"].tap()
    XCTAssertTrue(
      app.secureTextFields["Passphrase (at least 12 characters)"].waitForExistence(timeout: 5))
    capture("Backups", app: app)
    app.tabBars.buttons["Care"].tap()
    app.buttons["Add pregnancy"].tap()
    app.buttons["saveRecord"].tap()
    XCTAssertTrue(
      app.buttons.containing(.staticText, identifier: "Current pregnancy").firstMatch
        .waitForExistence(timeout: 10))
    app.tabBars.buttons["Today"].tap()
    XCTAssertTrue(
      app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Week ")).firstMatch
        .waitForExistence(timeout: 5))
  }
  @MainActor func testTimedObservationAndExclusion() throws {
    let app = XCUIApplication()
    app.launchArguments = ["-bloom.ui", "-bloom.reset", "-bloom.seed", "-bloom.light"]
    app.launch()
    XCTAssertTrue(app.buttons["logToday"].waitForExistence(timeout: 20))
    app.buttons["Add timed observation"].tap()
    let name = app.textFields["Observation name"]
    XCTAssertTrue(name.waitForExistence(timeout: 5))
    name.tap()
    name.press(forDuration: 1)
    if app.menuItems["Select All"].waitForExistence(timeout: 2) {
      app.menuItems["Select All"].tap()
    }
    name.typeText(" Focused")
    app.buttons["saveRecord"].tap()
    XCTAssertTrue(app.buttons["logToday"].waitForExistence(timeout: 10))
    app.tabBars.buttons["Calendar"].tap()
    app.swipeUp()
    XCTAssertTrue(app.buttons["Add timed observation"].waitForExistence(timeout: 5))
    XCTAssertTrue(
      app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Focused")).firstMatch
        .waitForExistence(timeout: 5))
    capture("Timed observation calendar", app: app)
    app.tabBars.buttons["Settings"].tap()
    app.buttons["Estimate exclusions"].tap()
    app.buttons["Exclude an interval"].tap()
    app.textFields["Reason"].tap()
    app.textFields["Reason"].typeText("Missing records")
    app.buttons["saveRecord"].tap()
    XCTAssertTrue(app.staticTexts["Missing records"].waitForExistence(timeout: 10))
    capture("Estimate exclusions", app: app)
  }

}
