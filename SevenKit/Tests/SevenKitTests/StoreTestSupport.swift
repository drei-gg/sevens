import Foundation
import SevenKit

final class StoreTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date
    init(_ date: Date = Date(timeIntervalSince1970: 1_800_000_000)) { current = date }
    var now: Date { lock.withLock { current } }
    func advance(_ seconds: TimeInterval) { lock.withLock { current = current.addingTimeInterval(seconds) } }
    var function: @Sendable () -> Date { { [self] in now } }
}

func makeTestDefaults() -> UserDefaults {
    let name = "seven.tests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name) ?? .standard
    defaults.removePersistentDomain(forName: name)
    return defaults
}

func testConfig(group: String? = nil) -> SevenConfig {
    SevenConfig(trialProductID: "org.example.sevens.qr.trial7", unlockProductID: "org.example.sevens.qr.unlock", appGroup: group)
}
