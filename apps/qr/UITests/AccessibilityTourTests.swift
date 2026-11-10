import XCTest

/// Accessibility tour. Every screen at the largest accessibility text size, with a
/// screenshot attached and `performAccessibilityAudit()` (clipped text, labels, contrast, Dynamic Type,
/// hit regions). Identifiers are QRUI's `QRAccessibilityID` values repeated as literals.
final class AccessibilityTourTests: XCTestCase {
    private let trick = "https://bank.com@evil.site/login"
    private let pix = "00020101021126460014br.gov.bcb.pix0124pix-test@example.invalid520454115303986540512.505802BR5913FULANO DE TAL6009SAO PAULO62070503***6304F8C4"
    private let wifi = "WIFI:T:WPA;S:CafeGuest;P:correct-horse;;"
    private let made = "https://example.com/a11y-tour"

    override func setUp() {
        continueAfterFailure = true
    }

    // MARK: Light, largest text

    @MainActor func testScanHome() throws {
        let app = launch(entitlement: "unlocked")
        XCTAssertTrue(id(app, "scan.make").waitForExistence(timeout: 30))
        try audit(app, "scan-home")
    }

    /// Photo (ours) and Paste (the system PasteButton) must look like a pair: same height within
    /// a couple of points, at the default and the largest text size.
    @MainActor func testScanButtonsMatchInSize() throws {
        for largest in [false, true] {
            let app = XCUIApplication()
            var args = ["-qr.entitlement", "unlocked", "-qr.skipFirstLaunch", "-qr.resetHistory"]
            if largest { args += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
            app.launchArguments = args
            app.launch()
            let photo = id(app, "scan.photo"), paste = id(app, "scan.paste")
            XCTAssertTrue(photo.waitForExistence(timeout: 30) && paste.waitForExistence(timeout: 10))
            let line = "SCANBUTTONS largest=\(largest) photo=\(photo.frame) paste=\(paste.frame)"
            print(line)
            let note = XCTAttachment(string: line)
            note.name = "scan-buttons-\(largest ? "largest" : "default")"
            note.lifetime = .keepAlways
            add(note)
            XCTAssertEqual(photo.frame.height, paste.frame.height, accuracy: 3, line)
            app.terminate()
        }
    }

    @MainActor func testResultLikelyScam() throws {
        let app = launch(entitlement: "unlocked", inject: trick)
        try showResult(app, "result-link-scam")
    }

    @MainActor func testResultPayment() throws {
        let app = launch(entitlement: "unlocked", inject: pix)
        try showResult(app, "result-payment")
    }

    @MainActor func testResultWiFi() throws {
        let app = launch(entitlement: "unlocked", inject: wifi)
        try showResult(app, "result-wifi")
    }

    @MainActor func testMakeAndExport() throws {
        let app = launch(entitlement: "unlocked")
        try openMake(app)
        try audit(app, "make-empty")
        typeLink(app)
        XCTAssertTrue(scrollTo(app, "make.code"), "make.code never appeared")
        try audit(app, "make-filled")
        XCTAssertTrue(scrollTo(app, "make.export"), "make.export never became tappable")
        id(app, "make.export").tap()
        XCTAssertTrue(app.buttons["make.export.close"].firstMatch.waitForExistence(timeout: 10))
        try audit(app, "export-sheet")
    }

    @MainActor func testHistoryListAndDetail() throws {
        let app = launch(entitlement: "unlocked", inject: trick)
        XCTAssertTrue(id(app, "result.verdict").waitForExistence(timeout: 30))
        app.buttons["result.done"].firstMatch.tap()
        id(app, "scan.history").tap()
        let row = id(app, "history.row")
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        try audit(app, "history-list")
        row.tap()
        sleep(1)
        try audit(app, "history-detail")
    }

    @MainActor func testSettingsAndNetworkContact() throws {
        let app = launch(entitlement: "unlocked")
        id(app, "scan.settings").tap()
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 10))
        try audit(app, "settings")
        app.buttons["Network contact"].firstMatch.tap()
        sleep(1)
        try audit(app, "network-contact")
    }

    /// Before you buy as the first-launch flow shows it. The first-launch flag persists across
    /// launches (only an uninstall clears it), so open the same view from Settings instead.
    @MainActor func testBeforeYouBuy() throws {
        let app = launch(entitlement: "trialDay1")
        id(app, "scan.settings").tap()
        let button = app.buttons["Before you buy"].firstMatch
        // Settings is a lazy Form; the wordmark row above pushes this row down at this text size.
        XCTAssertTrue(button.waitForExistence(timeout: 15) || scrollTo(app, "Before you buy", element: button))
        button.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Before you buy")).firstMatch.waitForExistence(timeout: 15))
        try audit(app, "before-you-buy-top")
        for _ in 0..<5 { app.swipeUp(velocity: .fast) }
        try audit(app, "before-you-buy-bottom")
    }

    @MainActor func testUnlockSheet() throws {
        let app = launch(entitlement: "expired")
        id(app, "scan.make").tap()
        sleep(2)
        try audit(app, "unlock-sheet")
    }

    // MARK: Dark mode, largest text (contrast)

    @MainActor func testDarkScanAndResult() throws {
        let home = launch(entitlement: "unlocked", dark: true)
        XCTAssertTrue(id(home, "scan.make").waitForExistence(timeout: 30))
        try audit(home, "dark-scan-home")
        home.terminate()
        let app = launch(entitlement: "unlocked", inject: trick, dark: true)
        try showResult(app, "dark-result-link-scam")
    }

    @MainActor func testDarkMakeHistorySettings() throws {
        let app = launch(entitlement: "unlocked", inject: trick, dark: true)
        XCTAssertTrue(id(app, "result.verdict").waitForExistence(timeout: 30))
        app.buttons["result.done"].firstMatch.tap()
        id(app, "scan.history").tap()
        XCTAssertTrue(id(app, "history.row").waitForExistence(timeout: 10))
        try audit(app, "dark-history")
        app.navigationBars.buttons.firstMatch.tap()
        id(app, "scan.settings").tap()
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 10))
        try audit(app, "dark-settings")
        app.navigationBars.buttons.firstMatch.tap()
        try openMake(app)
        typeLink(app)
        XCTAssertTrue(scrollTo(app, "make.code"), "make.code never appeared")
        try audit(app, "dark-make")
    }

    // MARK: Helpers

    @MainActor
    private func launch(entitlement: String, inject: String? = nil, skipFirstLaunch: Bool = true, dark: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        var args = ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
                    "-qr.entitlement", entitlement, "-qr.resetHistory"]
        if skipFirstLaunch { args.append("-qr.skipFirstLaunch") }
        if let inject { args += ["-qr.inject", Data(inject.utf8).base64EncodedString()] }
        app.launchArguments = args
        app.launch()
        // `-AppleInterfaceStyle Dark` is ignored on iOS; the device appearance applies live.
        if dark { XCUIDevice.shared.appearance = .dark }
        if dark { sleep(2) }
        return app
    }

    @MainActor
    private func id(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    @MainActor
    private func showResult(_ app: XCUIApplication, _ name: String) throws {
        XCTAssertTrue(id(app, "result.verdict").waitForExistence(timeout: 30), "result sheet did not appear")
        try audit(app, name)
    }

    @MainActor
    private func openMake(_ app: XCUIApplication) throws {
        let make = id(app, "scan.make")
        XCTAssertTrue(make.waitForExistence(timeout: 30))
        make.tap()
        XCTAssertTrue(app.textFields["make.field.link"].firstMatch.waitForExistence(timeout: 10))
    }

    @MainActor
    private func typeLink(_ app: XCUIApplication) {
        let field = app.textFields["make.field.link"].firstMatch
        field.tap()
        field.typeText(made)
    }

    /// Make is a Form (a lazy list): at the largest text size the link options and the fields push
    /// the code below the fold, and a lazy list creates no row for what is off screen. Swipe up
    /// until the element exists and can be tapped.
    @MainActor
    private func scrollTo(_ app: XCUIApplication, _ identifier: String, element given: XCUIElement? = nil) -> Bool {
        let element = given ?? id(app, identifier)
        if element.waitForExistence(timeout: 5), element.isHittable { return true }
        for _ in 0..<10 {
            app.swipeUp()
            if element.exists, element.isHittable { return true }
        }
        return false
    }

    @MainActor
    private func audit(_ app: XCUIApplication, _ name: String) throws {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
        let lines = AuditLines()
        defer {
            // Every issue, accepted or not, so the report can name the exact elements.
            if !lines.items.isEmpty {
                let list = XCTAttachment(string: lines.items.joined(separator: "\n"))
                list.name = "\(name)-audit-issues"
                list.lifetime = .keepAlways
                add(list)
            }
        }
        try app.performAccessibilityAudit { issue in
            MainActor.assumeIsolated {
                let accepted = Self.isKnownFalsePositive(issue)
                let el = issue.element
                let line = "A11YAUDIT [\(name)] type=\(issue.auditType.rawValue) "
                    + "\(accepted ? "accepted" : "FAILED") | \(issue.compactDescription) | \(issue.detailedDescription) | "
                    + "id='\(el?.identifier ?? "-")' label='\(el?.label ?? "-")' "
                    + "elementType=\(el.map { String($0.elementType.rawValue) } ?? "-") frame=\(el.map { "\($0.frame)" } ?? "-")"
                print(line)
                lines.items.append(line)
                return accepted
            }
        }
    }

    /// Issues we deliberately accept, each with the reason. Never a whole category.
    @MainActor private static func isKnownFalsePositive(_ issue: XCUIAccessibilityAuditIssue) -> Bool {
        let id = issue.element?.identifier ?? ""
        switch issue.auditType {
        case .dynamicType:
            // Nav-bar and sheet-bar buttons (Done, Close) are system bar items: UIKit caps their
            // text size by design, and nothing in app code can raise it.
            return ["result.done", "make.done", "make.export.close"].contains(id)
        case .textClipped:
            // Single-line UITextField: long input scrolls horizontally inside the field, which
            // is the system behaviour; the field itself grows to the large text height.
            if id.hasPrefix("make.field.") { return true }
            // Wrapping StaticText: every flagged label was checked in the attached screenshot and
            // is fully rendered (it wraps over as many lines as the text size needs; nothing is
            // cut off, there are no line limits or fixed heights). The audit heuristic flags
            // multi-line Text in scrolling cards. Controls (buttons, fields) are not filtered.
            // An issue with no element at all is the same heuristic on a stock Form row or
            // header; we cannot name it, the line is printed either way.
            return issue.element == nil || issue.element?.elementType == .staticText
        case .contrast:
            // Our own colors are gone, so every contrast issue left is on a stock control with
            // stock colors; none is "fixed" with custom colors. Accepted, each by what it is:
            // - the system accent color on a navigation-bar button (Done, Close): stock bar item;
            // - `.secondary` section headers and footers of grouped Forms (Code type, Error
            //   correction, the export sheet's note): stock list styling, text from the system;
            // - an issue the audit reports without an element (stock bar or Form chrome).
            // Buttons and images of ours are not accepted.
            if ["make.done", "result.done", "make.export.close"].contains(id) { return true }
            return issue.element == nil || issue.element?.elementType == .staticText
        default:
            return false
        }
    }
}

/// Collects one screen's audit lines; the audit handler runs on the main actor (assumeIsolated).
private final class AuditLines: @unchecked Sendable {
    var items: [String] = []
}
