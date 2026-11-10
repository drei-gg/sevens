import Testing
import QRCore
@testable import QRUI

// Phase 0: proves QRUI, QRCore and SevenKit link and that QRCore's resources load on iOS.
@Test func bundledDataLoadsInTheSimulator() {
    _ = BundledData.load()
}
