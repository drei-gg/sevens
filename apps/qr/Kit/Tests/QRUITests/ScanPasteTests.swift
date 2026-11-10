import Foundation
import QRCore
import Testing
import UIKit
@testable import QRUI

@MainActor
@Suite struct ScanPasteTests {
    @Test func scanPasteTextBecomesOneQRCode() async {
        let provider = NSItemProvider(object: "  hello world \n" as NSString)
        let codes = await PasteScanButton.codes(from: [provider])
        #expect(codes == [ScannedCode(text: "hello world", symbology: .qr)])
    }

    @Test func scanPasteURLBecomesOneQRCode() async throws {
        let url = try #require(URL(string: "https://example.com/path?q=1"))
        let codes = await PasteScanButton.codes(from: [NSItemProvider(object: url as NSURL)])
        #expect(codes == [ScannedCode(text: "https://example.com/path?q=1", symbology: .qr)])
    }

    @Test(.enabled(if: ScanTestImages.visionAvailable)) func scanPasteImageIsScanned() async throws {
        let cg = try #require(ScanTestImages.single(ScanTestImages.qr("pasted picture")))
        let provider = NSItemProvider(object: UIImage(cgImage: cg))
        let codes = await PasteScanButton.codes(from: [provider])
        #expect(codes.map(\.text) == ["pasted picture"])
    }

    @Test func scanPasteImageWithoutCodeFallsBackToNothing() async throws {
        let cg = try #require(ScanTestImages.blank())
        let codes = await PasteScanButton.codes(from: [NSItemProvider(object: UIImage(cgImage: cg))])
        #expect(codes.isEmpty)
    }

    @Test func scanPasteEmptyAndOversizedTextAreIgnored() async {
        #expect(await PasteScanButton.codes(from: []).isEmpty)
        #expect(await PasteScanButton.codes(from: [NSItemProvider(object: "   " as NSString)]).isEmpty)
        let huge = String(repeating: "a", count: PasteScanButton.maxTextBytes + 1)
        #expect(await PasteScanButton.codes(from: [NSItemProvider(object: huge as NSString)]).isEmpty)
    }
}
