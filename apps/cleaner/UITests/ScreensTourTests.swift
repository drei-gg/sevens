import XCTest
@MainActor final class ScreensTourTests: XCTestCase {
    private var contrastFailures: [String] = []
    let routes = ["overview","photos","videos","files","contacts","groups","pending","photo","video","compression","contact-groups","contact-comparison","contact-fields","settings","activity","country","onboarding","unlock","expired","license-phone","license-metadata","delete-dialog","empty-overview","empty-photos","empty-videos","empty-files","empty-contacts"]
    func testEveryScreenLightAndDark() throws {
        for theme in ["light","dark"] { for route in routes { try capture(route,theme: theme,large: false) } }
        XCTAssertTrue(contrastFailures.isEmpty,contrastFailures.joined(separator: "\n"))
    }
    func testAccessibilityLayouts() throws {
        for route in ["overview","photos","videos","files","contacts","compression","contact-comparison"] { try capture(route,theme: "dark",large: true) }
        XCTAssertTrue(contrastFailures.isEmpty,contrastFailures.joined(separator: "\n"))
    }
    private func capture(_ route: String, theme: String, large: Bool) throws {
        let app = XCUIApplication()
        app.launchArguments = ["-cleaner.ui","-cleaner.reset","-cleaner.tour",route,"-cleaner.theme",theme]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName","UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        XCTAssertTrue(app.otherElements["cleaner.tour.ready"].waitForExistence(timeout: 30),"Tour not reached: \(route)")
        if route == "country" {
            let button = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Phone number country")).firstMatch
            XCTAssertTrue(button.waitForExistence(timeout: 5),"Tour missing: country picker"); button.tap()
            XCTAssertTrue(app.buttons["Afghanistan"].waitForExistence(timeout: 5),"Tour not reached: country list")
        }
        if route == "contact-groups" {
            let button = app.buttons["Compare contacts"]
            XCTAssertTrue(button.waitForExistence(timeout: 5),"Tour missing: contacts primary action"); button.tap()
            XCTAssertTrue(app.navigationBars["Contact candidates"].waitForExistence(timeout: 5),"Tour not reached: contact candidates")
        }
        if route == "contact-comparison" {
            let button = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Alex Rivera")).firstMatch
            XCTAssertTrue(button.waitForExistence(timeout: 5),"Tour missing: contact comparison"); button.tap()
        }
        if route == "delete-dialog" {
            let button = app.buttons["Delete reviewed items…"]
            XCTAssertTrue(button.waitForExistence(timeout: 5),"Tour missing: delete confirmation"); button.tap()
            XCTAssertTrue(app.buttons["Delete from Photos"].waitForExistence(timeout: 5),"Tour not reached: delete dialog")
        }
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "\(theme)-\(route)\(large ? "-AX5" : "")"; shot.lifetime = .keepAlways; add(shot)
        try auditOnPhone(app,name: shot.name ?? route)
        if ["overview","photos","videos","files","contacts","groups","settings","photo","video","compression","contact-fields","contact-comparison","license-phone","license-metadata"].contains(route) || large {
            app.swipeUp()
            let lower = XCTAttachment(screenshot: app.screenshot()); lower.name = "\(theme)-\(route)\(large ? "-AX5" : "")-lower"; lower.lifetime = .keepAlways; add(lower)
            try auditOnPhone(app,name: lower.name ?? route + "-lower")
        }
        app.terminate()
    }
    private func auditOnPhone(_ app: XCUIApplication,name: String) throws {
        #if !targetEnvironment(simulator)
        let issues = ContrastLines()
        try app.performAccessibilityAudit(for: .contrast) { issue in
            MainActor.assumeIsolated {
                // Shared native-polish baseline: unattributed system chrome and static text
                // outside the window (iOS fades scroll edges). Visible text is never exempt.
                let element = issue.element
                let baseline = element == nil || (element?.elementType == .staticText && !app.windows.firstMatch.frame.contains(element!.frame))
                let line = "CONTRAST [\(name)] \(baseline ? "baseline" : "FAILED") | \(issue.compactDescription) | label=\(element?.label ?? "<none>") frame=\(element.map { "\($0.frame)" } ?? "<none>")"
                print(line); issues.lines.append(line)
                if !baseline { issues.failures.append(line) }
                return true // Gather every issue; the test asserts the collected failures.
            }
        }
        contrastFailures += issues.failures
        if !issues.lines.isEmpty {
            let attachment = XCTAttachment(string: issues.lines.joined(separator: "\n"))
            attachment.name = name + "-contrast"; attachment.lifetime = .keepAlways; add(attachment)
        }
        #endif
    }

}

private final class ContrastLines: @unchecked Sendable { var lines: [String] = []; var failures: [String] = [] }
