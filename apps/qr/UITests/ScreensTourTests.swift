import XCTest

/// The screenshot tour: every screen, light and dark, each attached as `light-03-result-likely-scam`
/// and so on. It is for people reading the app, not a gate, so ordinary `scripts/vm ui` skips it;
/// run it with `scripts/vm screens` and export the pictures with `scripts/export-screens`.
/// Steps that cannot reach their screen note it in `tour-notes` instead of stopping the tour.
final class ScreensTourTests: XCTestCase {
    private let trick = "https://bank.com@evil.site/login"
    private let normalLink = "https://www.apple.com/iphone/"
    private let renamedBrand = "https://paypal.pt/login"
    private let pix = "00020101021126460014br.gov.bcb.pix0124pix-test@example.invalid520454115303986540512.505802BR5913FULANO DE TAL6009SAO PAULO62070503***6304F8C4"
    private let openWiFi = "WIFI:T:nopass;S:Corner Cafe Guest;;"
    private let contact = "BEGIN:VCARD\nVERSION:3.0\nFN:Ana Reyes\nORG:Reyes Bakery\nTITLE:Owner\nTEL:+639171234567\nEMAIL:ana@example.com\nEND:VCARD"
    private let plainText = "Meet at the north gate at six. Bring the blue folder."
    private let madeLink = "https://example.com/menu/spring-specials/lunch-set-for-two-people"
    private let trackingSuffix = "?utm_source=flyer&utm_medium=print"

    private var mode = "light"
    private var counter = 0
    private var notes: [String] = []

    override func setUp() {
        continueAfterFailure = true
    }

    @MainActor override func tearDown() async throws {
        let note = XCTAttachment(string: notes.isEmpty ? "every screen was reached" : notes.joined(separator: "\n"))
        note.name = "\(mode)-tour-notes"
        note.lifetime = .keepAlways
        add(note)
        XCUIDevice.shared.appearance = .light
    }

    @MainActor func testLightTour() { tour("light") }
    @MainActor func testDarkTour() { tour("dark") }

    @MainActor
    private func tour(_ name: String) {
        mode = name
        XCUIDevice.shared.appearance = name == "dark" ? .dark : .light
        scanAndResults()
        peek()
        knownAddress()
        make()
        historyAndSettings()
        unlockScreens()
    }

    // MARK: Stages

    @MainActor private func scanAndResults() {
        var app = launch()
        if id(app, "scan.make").waitForExistence(timeout: 30) { shoot(app, "scan-home") }
        app.terminate()
        app = launch(largestText: true)
        if id(app, "scan.make").waitForExistence(timeout: 30) { shoot(app, "scan-home-largest-text") }
        app.terminate()

        let results: [(String, String, String)] = [
            ("result-likely-scam", trick, "result.verdict"),
            ("result-link-normal", normalLink, "result.verdict"),
            ("result-payment", pix, "result.verdict"),
            ("result-wifi-open", openWiFi, "result.verdict"),
            ("result-contact", contact, "result.verdict"),
            ("result-plain-text", plainText, "result.verdict"),
        ]
        for (name, payload, marker) in results {
            app = launch(inject: payload)
            if id(app, marker).waitForExistence(timeout: 30) {
                shoot(app, name)
                if scrollDown(app) { shoot(app, "\(name)-more") }
            } else {
                notes.append("\(name): the result sheet did not appear")
            }
            app.terminate()
        }
    }

    @MainActor private func peek() {
        let app = launch(inject: normalLink)
        guard id(app, "result.verdict").waitForExistence(timeout: 30) else { notes.append("peek: no result sheet"); return }
        guard scrollTo(app, "result.peek") else { notes.append("peek: Peek button not found"); return }
        shoot(app, "result-before-peek")
        id(app, "result.peek").tap()
        // Peek uses the real network; the VM has it. If it were offline this shows "couldn't finish".
        if !id(app, "peek.verdict").waitForExistence(timeout: 40) { notes.append("peek: no verdict within 40 s (network?)") }
        shoot(app, "peek")
        if scrollDown(app) { shoot(app, "peek-more") }
        app.terminate()
    }

    @MainActor private func knownAddress() {
        let app = launch(inject: renamedBrand)
        guard id(app, "result.verdict").waitForExistence(timeout: 30) else { notes.append("known: no result sheet"); return }
        shoot(app, "result-careful-brand-ending")
        guard scrollTo(app, "result.known.know") else { notes.append("known: the question did not appear"); return }
        shoot(app, "result-i-know-this-address")
        id(app, "result.known.know").tap()
        sleep(1)
        shoot(app, "result-after-i-know-this-address")
        if app.buttons["result.done"].firstMatch.exists { app.buttons["result.done"].firstMatch.tap() }
        id(app, "scan.settings").tap()
        if scrollTo(app, "settings.known.row") { shoot(app, "settings-addresses-you-know") }
        else { notes.append("known: Addresses you know not found in Settings") }
        app.terminate()
    }

    @MainActor private func make() {
        let app = launch()
        let make = id(app, "scan.make")
        guard make.waitForExistence(timeout: 30) else { notes.append("make: no Make button"); return }
        make.tap()
        let field = app.textFields["make.field.link"].firstMatch
        guard field.waitForExistence(timeout: 10) else { notes.append("make: no link field"); return }
        shoot(app, "make-empty")
        field.tap()
        field.typeText(madeLink)
        sleep(1)
        shoot(app, "make-link-options")
        if scrollTo(app, "make.link.smaller") {
            shoot(app, "make-same-link-smaller-code")
            id(app, "make.link.smaller").tap()
            sleep(1)
            shoot(app, "make-same-link-smaller-code-off")
        } else { notes.append("make: the 'Same link, smaller code' switch did not appear") }
        field.tap()
        field.typeText(trackingSuffix)
        sleep(1)
        if scrollTo(app, "make.link.tracking.remove") { shoot(app, "make-tracking-tags-offer") }
        else { notes.append("make: the tracking-tags offer did not appear") }
        if scrollTo(app, "make.code") { shoot(app, "make-link-code") } else { notes.append("make: no code shown") }
        if scrollTo(app, "make.export") {
            id(app, "make.export").tap()
            if app.buttons["make.export.close"].firstMatch.waitForExistence(timeout: 10) { shoot(app, "make-export-sheet") }
            else { notes.append("make: export sheet did not open") }
        }
        app.terminate()
    }

    @MainActor private func historyAndSettings() {
        var app = launch(inject: trick)
        guard id(app, "result.verdict").waitForExistence(timeout: 30) else { notes.append("history: no result sheet"); return }
        app.buttons["result.done"].firstMatch.tap()
        id(app, "scan.history").tap()
        let row = id(app, "history.row")
        if row.waitForExistence(timeout: 10) {
            shoot(app, "history-list")
            row.tap()
            sleep(1)
            shoot(app, "history-detail")
        } else { notes.append("history: no row") }
        app.terminate()

        app = launch()
        id(app, "scan.settings").tap()
        if app.navigationBars.firstMatch.waitForExistence(timeout: 10) {
            shoot(app, "settings")
            for page in 1...3 {
                guard scrollDown(app) else { break }
                shoot(app, "settings-\(page)")
            }
        }
        app.terminate()

        app = launch()
        id(app, "scan.settings").tap()
        let button = app.buttons["Before you buy"].firstMatch
        if scrollTo(app, "Before you buy", element: button) {
            button.tap()
            sleep(1)
            shoot(app, "before-you-buy")
        } else { notes.append("before you buy: row not found in Settings") }
        app.terminate()
    }

    @MainActor private func unlockScreens() {
        // The debug preset persists but is applied after the store starts, so the first launch
        // only sets the state; the sheet appears at the next launch.
        launch(entitlement: "expired").terminate()
        var app = launch(entitlement: nil)
        // The trial-ended sheet appears once at launch when the trial has ended.
        if app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "7 days are over")).firstMatch.waitForExistence(timeout: 15) {
            shoot(app, "trial-ended")
            let notNow = app.buttons["Not now"].firstMatch
            if notNow.exists { notNow.tap() }
        } else { notes.append("trial ended: the sheet did not appear at the second launch with the expired state") }
        sleep(1)
        let make = id(app, "scan.make")
        if make.waitForExistence(timeout: 10) {
            make.tap()
            sleep(2)
            shoot(app, "unlock")
        }
        app.terminate()

        app = launch(entitlement: "unlocked", skipFirstLaunch: false)
        if app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Before you buy")).firstMatch
            .waitForExistence(timeout: 20) {
            shoot(app, "first-launch-before-you-buy")
        } else { notes.append("first launch: Before you buy did not appear") }
        app.terminate()
    }

    // MARK: Helpers

    @MainActor @discardableResult
    private func launch(entitlement: String? = "unlocked", inject: String? = nil, skipFirstLaunch: Bool = true,
                        largestText: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        var args = ["-qr.resetHistory", "-qr.resetKnown", "-qr.resetFlags"]
        if let entitlement { args += ["-qr.entitlement", entitlement] }
        if skipFirstLaunch { args.append("-qr.skipFirstLaunch") }
        if largestText { args += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        if let inject { args += ["-qr.inject", Data(inject.utf8).base64EncodedString()] }
        app.launchArguments = args
        app.launch()
        return app
    }

    @MainActor
    private func id(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    @MainActor
    private func scrollDown(_ app: XCUIApplication) -> Bool {
        let before = app.screenshot().pngRepresentation
        app.swipeUp()
        sleep(1)
        return app.screenshot().pngRepresentation != before
    }

    /// Lists create no rows off screen: swipe up until the element exists and can be tapped.
    @MainActor
    private func scrollTo(_ app: XCUIApplication, _ identifier: String, element: XCUIElement? = nil) -> Bool {
        let target = element ?? id(app, identifier)
        if target.waitForExistence(timeout: 5), target.isHittable { return true }
        for _ in 0..<10 {
            app.swipeUp()
            if target.exists, target.isHittable { return true }
        }
        return false
    }

    @MainActor
    private func shoot(_ app: XCUIApplication, _ name: String) {
        counter += 1
        sleep(1)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = String(format: "%@-%02d-%@", mode, counter, name)
        shot.lifetime = .keepAlways
        add(shot)
    }
}
