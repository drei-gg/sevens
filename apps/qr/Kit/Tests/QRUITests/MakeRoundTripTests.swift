import CoreGraphics
import Foundation
import ImageIO
import QRCore
import Testing
import CoreImage
import Vision
@testable import QRUI

enum MakeTestSupport {
    static func decode(_ png: Data) throws -> [String] {
        let request = VNDetectBarcodesRequest()
        let handler = VNImageRequestHandler(data: png, options: [:])
        try handler.perform([request])
        return (request.results ?? []).compactMap { $0.payloadStringValue }
    }

    /// Vision's barcode detector returns nothing on the iOS simulator (no inference backend there;
    /// verified: the same PNGs decode on macOS). A canary QR tells the Vision tests whether to run.
    static let visionAvailable: Bool = {
        guard let m = try? CodeGenerator.generate(text: "canary", symbology: .qr, correction: .M) else { return false }
        let png = PNGRenderer.png(matrix: m, pixelSize: 512, foreground: .black, background: .white)
        return (try? decode(png)) == ["canary"]
    }()

    /// QR decode through CoreImage's detector (works on the simulator).
    static func decodeQRWithCoreImage(_ png: Data) -> [String] {
        guard let image = CIImage(data: png),
              let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        else { return [] }
        return detector.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }

    static func pngSize(_ data: Data) -> (Int, Int)? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int,
              let h = props[kCGImagePropertyPixelHeight] as? Int else { return nil }
        return (w, h)
    }

    static func roundTrip(_ text: String, _ symbology: MakeSymbology, _ correction: Correction) throws -> [String] {
        let m = try CodeGenerator.generate(text: text, symbology: symbology, correction: correction)
        let png = PNGRenderer.png(matrix: m, pixelSize: 1024, foreground: .black, background: .white)
        return try decode(png)
    }
}

@Suite struct MakeRoundTripTests {
    @Test(.enabled(if: MakeTestSupport.visionAvailable), arguments: Correction.allCases)
    func qrRoundTrip(correction: Correction) throws {
        let text = "https://example.com/make?x=1&y=two"
        #expect(try MakeTestSupport.roundTrip(text, .qr, correction) == [text])
    }

    @Test(.enabled(if: MakeTestSupport.visionAvailable)) func aztecRoundTrip() throws {
        let text = "Aztec ticket 0042-7&D"
        #expect(try MakeTestSupport.roundTrip(text, .aztec, .auto) == [text])
    }

    @Test(.enabled(if: MakeTestSupport.visionAvailable), arguments: [Correction.L, .H])
    func aztecCorrectionLevels(correction: Correction) throws {
        let text = "Aztec level test"
        #expect(try MakeTestSupport.roundTrip(text, .aztec, correction) == [text])
    }

    @Test(.enabled(if: MakeTestSupport.visionAvailable)) func pdf417RoundTrip() throws {
        let text = "PDF417 boarding pass: SMITH/JOHN 0123456789"
        #expect(try MakeTestSupport.roundTrip(text, .pdf417, .auto) == [text])
    }

    @Test(.enabled(if: MakeTestSupport.visionAvailable)) func code128RoundTrip() throws {
        let text = "SKU-12345-ABC"
        #expect(try MakeTestSupport.roundTrip(text, .code128, .auto) == [text])
    }

    @Test(.enabled(if: MakeTestSupport.visionAvailable)) func qrUnicodeRoundTrip() throws {
        let text = "Café ☕ 日本"
        #expect(try MakeTestSupport.roundTrip(text, .qr, .M) == [text])
    }
}

@Suite struct MakeGeneratorTests {
    @Test func emptyIsRejected() {
        #expect(throws: MakeError.empty) { try CodeGenerator.generate(text: "", symbology: .qr, correction: .auto) }
    }

    @Test func longInputIsRejected() {
        let long = String(repeating: "A", count: 5000)
        #expect(throws: MakeError.tooLong) { try CodeGenerator.generate(text: long, symbology: .qr, correction: .H) }
        let huge = String(repeating: "A", count: 20000)
        #expect(throws: MakeError.tooLong) { try CodeGenerator.generate(text: huge, symbology: .aztec, correction: .auto) }
    }

    @Test func code128RejectsNonASCII() {
        #expect(throws: MakeError.notASCII) { try CodeGenerator.generate(text: "café", symbology: .code128, correction: .auto) }
    }

    @Test func errorMessagesAreNotEmpty() {
        for e in [MakeError.empty, .tooLong, .notASCII, .generatorFailed] { #expect(!e.message.isEmpty) }
    }

    @Test(arguments: MakeSymbology.allCases)
    func quietZoneIsLight(symbology: MakeSymbology) throws {
        let m = try CodeGenerator.generate(text: "Quiet 123", symbology: symbology, correction: .auto)
        let q = QuietZone.modules(for: symbology.symbology)
        for y in 0..<m.height {
            for x in 0..<m.width where x < q.horizontal || x >= m.width - q.horizontal
                || y < q.vertical || y >= m.height - q.vertical {
                #expect(!m[x, y])
            }
        }
        // The edge just inside the quiet zone has dark modules (no extra built-in margin left).
        let inner = (q.horizontal..<(m.width - q.horizontal)).contains { x in
            (q.vertical..<(m.height - q.vertical)).contains { y in x == q.horizontal && m[x, y] }
        }
        #expect(inner)
    }

    @Test func code128IsOneRowPlusQuietZone() throws {
        let m = try CodeGenerator.generate(text: "ABC123", symbology: .code128, correction: .auto)
        #expect(m.height == 1)
        #expect(!(0..<10).contains { m[$0, 0] })
        #expect(m[10, 0])
    }

    @Test func qrSizeMatchesVersion() throws {
        let m = try CodeGenerator.generate(text: "hi", symbology: .qr, correction: .L)
        #expect(m.width == 21 + 8 && m.height == m.width)
    }

    @Test func higherCorrectionNeverShrinksQR() throws {
        let text = String(repeating: "x", count: 60)
        let l = try CodeGenerator.generate(text: text, symbology: .qr, correction: .L)
        let h = try CodeGenerator.generate(text: text, symbology: .qr, correction: .H)
        #expect(h.width >= l.width)
        let auto = try CodeGenerator.generate(text: text, symbology: .qr, correction: .auto)
        let mm = try CodeGenerator.generate(text: text, symbology: .qr, correction: .M)
        #expect(auto == mm)
    }
}

@Suite struct MakeRendererTests {
    @Test func pngDimensionsAreScaleTimesModules() throws {
        let m = try CodeGenerator.generate(text: "dims", symbology: .qr, correction: .M)
        for size in [512, 1024, 2048] {
            let png = PNGRenderer.png(matrix: m, pixelSize: size, foreground: .black, background: .white)
            let s = size / m.width
            let dims = try #require(MakeTestSupport.pngSize(png))
            #expect(dims.0 == s * m.width && dims.1 == s * m.height)
            #expect(dims.0 <= size)
        }
    }

    @Test func pngSizeIsClampedAndMinimumScaleIsOne() throws {
        let m = try CodeGenerator.generate(text: "dims", symbology: .qr, correction: .M)
        let big = PNGRenderer.png(matrix: m, pixelSize: 100_000, foreground: .black, background: .white)
        let d = try #require(MakeTestSupport.pngSize(big))
        #expect(d.0 <= PNGRenderer.maxPixelSize)
        let tiny = PNGRenderer.png(matrix: m, pixelSize: 1, foreground: .black, background: .white)
        let t = try #require(MakeTestSupport.pngSize(tiny))
        #expect(t.0 == m.width)
    }

    @Test func code128PngUsesBarHeight() throws {
        let m = try CodeGenerator.generate(text: "ABC", symbology: .code128, correction: .auto)
        let png = PNGRenderer.png(matrix: m, pixelSize: 1024, foreground: .black, background: .white)
        let d = try #require(MakeTestSupport.pngSize(png))
        let s = d.0 / m.width
        #expect(d.1 == s * MakeGeometry.rows(for: m))
        #expect(d.1 > s)
    }

    @Test(.enabled(if: MakeTestSupport.visionAvailable)) func coloredPngStillDecodes() throws {
        let m = try CodeGenerator.generate(text: "colour", symbology: .qr, correction: .Q)
        let png = PNGRenderer.png(matrix: m, pixelSize: 1024, foreground: RGBColor(red: 0, green: 0.2, blue: 0.5), background: .white)
        #expect(try MakeTestSupport.decode(png) == ["colour"])
    }

    @Test func pdfSingleIsOnePage() throws {
        let m = try CodeGenerator.generate(text: "pdf", symbology: .qr, correction: .M)
        for paper in PaperSize.allCases {
            let data = PDFRenderer.single(matrix: m, caption: "My code", foreground: .black, background: .white, paper: paper)
            #expect(!data.isEmpty)
            let doc = try #require(CGDataProvider(data: data as CFData).flatMap { CGPDFDocument($0) })
            #expect(doc.numberOfPages == 1)
        }
        let noCaption = PDFRenderer.single(matrix: m, caption: nil, paper: .a4)
        #expect(!noCaption.isEmpty)
    }

    @Test func pdfSheetIsOnePageForEveryGrid() throws {
        let m = try CodeGenerator.generate(text: "sheet", symbology: .code128, correction: .auto)
        for grid in SheetGrid.allCases {
            let data = PDFRenderer.sheet(matrix: m, caption: "Label", grid: grid, paper: .letter)
            let doc = try #require(CGDataProvider(data: data as CFData).flatMap { CGPDFDocument($0) })
            #expect(doc.numberOfPages == 1)
        }
    }

    @Test func svgConvenienceIsUTF8() throws {
        let m = try CodeGenerator.generate(text: "svg", symbology: .qr, correction: .M)
        let data = SVGRenderer.data(matrix: m, moduleSize: 4, foreground: .black, background: .white)
        #expect(String(data: data, encoding: .utf8)?.hasPrefix("<svg") == true)
    }
}
