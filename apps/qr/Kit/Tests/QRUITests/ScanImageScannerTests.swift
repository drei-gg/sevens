import CoreImage
import QRCore
import Testing
import UIKit
@testable import QRUI

@Suite struct ScanImageScannerTests {
    @Test(.enabled(if: ScanTestImages.visionAvailable)) func scanOneQR() async throws {
        let image = try #require(ScanTestImages.single(ScanTestImages.qr("https://example.com/one")))
        let codes = await ImageScanner().scan(image)
        #expect(codes == [ScannedCode(text: "https://example.com/one", symbology: .qr)])
    }

    @Test(.enabled(if: ScanTestImages.visionAvailable)) func scanThreeQRSideBySide() async throws {
        let texts = ["alpha", "bravo", "charlie"]
        let big = texts.map { ScanTestImages.scaled(ScanTestImages.qr($0), by: 8) }
        let w = big[0].extent.width
        let gap: CGFloat = 60
        let placed = big.enumerated().map { (i, img) in
            (img, CGPoint(x: gap + CGFloat(i) * (w + gap), y: gap))
        }
        let size = CGSize(width: gap + 3 * (w + gap) - gap + gap, height: big[0].extent.height + 2 * gap)
        let image = try #require(ScanTestImages.canvas(placed, size: size))
        let codes = await ImageScanner().scan(image)
        #expect(codes.map(\.text) == texts)
        #expect(codes.allSatisfy { $0.symbology == .qr })
    }

    @Test func scanBlankImageFindsNothing() async throws {
        let image = try #require(ScanTestImages.blank())
        #expect(await ImageScanner().scan(image).isEmpty)
    }

    @Test(.enabled(if: ScanTestImages.visionAvailable)) func scanRotatedQR() async throws {
        for degrees: CGFloat in [30, 45] {
            let image = try #require(ScanTestImages.rotated(ScanTestImages.qr("rotated code"), degrees: degrees))
            let codes = await ImageScanner().scan(image)
            #expect(codes.map(\.text) == ["rotated code"], "at \(degrees) degrees")
        }
    }

    @Test(.enabled(if: ScanTestImages.visionAvailable)) func scanAztec() async throws {
        let image = try #require(ScanTestImages.single(ScanTestImages.aztec("aztec payload 123")))
        let codes = await ImageScanner().scan(image)
        #expect(codes.map(\.text) == ["aztec payload 123"])
        #expect(codes.map(\.symbology) == [.aztec])
    }

    @Test(.enabled(if: ScanTestImages.visionAvailable)) func scanFromPNGData() async throws {
        let image = try #require(ScanTestImages.single(ScanTestImages.qr("from data")))
        let data = try #require(ScanTestImages.pngData(image))
        #expect(await ImageScanner().scan(data).map(\.text) == ["from data"])
        #expect(await ImageScanner().scan(UIImage(cgImage: image)).map(\.text) == ["from data"])
    }

    @Test func scanGarbageDataFindsNothing() async {
        #expect(await ImageScanner().scan(Data([1, 2, 3, 4])).isEmpty)
        #expect(await ImageScanner().scan(Data()).isEmpty)
    }

    @Test(.enabled(if: ScanTestImages.visionAvailable)) func scanDownscalesLargeImages() async throws {
        // 3000 px wide: the long side must come down to 2048 and the code must still read.
        let image = try #require(ScanTestImages.single(ScanTestImages.qr("big picture"), scale: 40, margin: 1000))
        #expect(max(image.width, image.height) > ImageScanner.maxPixelSize)
        let small = ImageScanner.downscaled(image)
        #expect(max(small.width, small.height) == ImageScanner.maxPixelSize)
        #expect(await ImageScanner().scan(image).map(\.text) == ["big picture"])
        let data = try #require(ScanTestImages.pngData(image))
        #expect(await ImageScanner().scan(data).map(\.text) == ["big picture"])
    }

    @Test func scanDownscaleLeavesSmallImagesAlone() throws {
        let image = try #require(ScanTestImages.blank())
        let same = ImageScanner.downscaled(image)
        #expect(same.width == image.width && same.height == image.height)
    }

    // Replacement coverage that runs wherever CoreImage does (QR only).
    @Test func scanGeneratedImagesDecodeWithCIDetector() throws {
        let one = try #require(ScanTestImages.single(ScanTestImages.qr("https://example.com/one")))
        #expect(ScanTestImages.detectQR(one) == ["https://example.com/one"])
        let rotated = try #require(ScanTestImages.rotated(ScanTestImages.qr("rotated code"), degrees: 30))
        #expect(ScanTestImages.detectQR(rotated) == ["rotated code"])
        let blank = try #require(ScanTestImages.blank())
        #expect(ScanTestImages.detectQR(blank).isEmpty)
    }

    @Test func scanDownscaledImageStillDecodes() throws {
        let image = try #require(ScanTestImages.single(ScanTestImages.qr("big picture"), scale: 40, margin: 1000))
        let small = ImageScanner.downscaled(image)
        #expect(max(small.width, small.height) == ImageScanner.maxPixelSize)
        #expect(ScanTestImages.detectQR(small) == ["big picture"])
    }

    // MARK: ordering and de-duplication

    private func found(_ text: String, x: CGFloat, y: CGFloat, size: CGFloat = 0.1) -> ImageScanner.Found {
        .init(code: ScannedCode(text: text), box: CGRect(x: x, y: y, width: size, height: size))
    }

    @Test func scanOrderIsTopToBottomThenLeftToRight() {
        // Vision's y grows upward, so a larger y is higher in the picture.
        let ordered = ImageScanner.order([
            found("bottom-left", x: 0.1, y: 0.1),
            found("top-right", x: 0.7, y: 0.8),
            found("top-left-slightly-lower", x: 0.1, y: 0.78),
            found("bottom-right", x: 0.7, y: 0.11),
        ])
        #expect(ordered.map(\.text) == ["top-left-slightly-lower", "top-right", "bottom-left", "bottom-right"])
    }

    @Test func scanOrderDropsIdenticalPayloads() {
        let ordered = ImageScanner.order([
            found("same", x: 0.6, y: 0.5),
            found("same", x: 0.1, y: 0.5),
            found("other", x: 0.3, y: 0.1),
        ])
        #expect(ordered.map(\.text) == ["same", "other"])
    }

    @Test func scanOrderKeepsSamePayloadInDifferentSymbologies() {
        let a = ImageScanner.Found(code: ScannedCode(text: "x", symbology: .qr), box: CGRect(x: 0, y: 0.5, width: 0.1, height: 0.1))
        let b = ImageScanner.Found(code: ScannedCode(text: "x", symbology: .aztec), box: CGRect(x: 0.5, y: 0.5, width: 0.1, height: 0.1))
        #expect(ImageScanner.order([a, b]).count == 2)
    }
}
