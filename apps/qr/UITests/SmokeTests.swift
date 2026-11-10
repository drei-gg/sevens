import XCTest

// WP 4.1 replaces this with the PLAN §8 smoke test.
final class SmokeTests: XCTestCase {
    @MainActor
    func testLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))
    }
}
