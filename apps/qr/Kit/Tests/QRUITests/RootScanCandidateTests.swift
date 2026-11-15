import Testing
import QRCore
@testable import QRUI

@Suite @MainActor struct RootScanCandidateTests {
    @Test func textCodeHasTypeNameAndNoDomain() {
        let analysis = AppModel.previewAnalyze(ScannedCode(text: "hello"))
        let candidate = ScanScreenCandidate(index: 0, analysis: analysis)
        #expect(candidate.typeName == VerdictText.typeName(for: "text"))
        #expect(candidate.domain == nil)
        #expect(candidate.rowLabel == candidate.typeName)
    }

    @Test func routesAreDistinct() {
        #expect(Set([RootRoute.make, .history, .settings]).count == 3)
    }
}
