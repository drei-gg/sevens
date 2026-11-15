import Testing
import QRCore
@testable import QRUI

// Proves QRUI, QRCore (with the Rust core's static library) and SevenKit link, and the bundled lists load on iOS.
@Test func bundledDataLoadsInTheSimulator() {
    _ = BundledData.load()
}
