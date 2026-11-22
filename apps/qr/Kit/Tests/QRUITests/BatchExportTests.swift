import CoreGraphics
import Foundation
import QRCore
import Testing
import UIKit
@testable import QRUI

@Test func batchPdfPreservesEveryPayloadAcrossPageBoundaries() throws {
    let items = (1...7).map { "https://example.org/item/\($0)?utm_source=labels" }
    let data = try BatchExport.pdf(items: items, grid: .grid2x3, paper: .a4)
    let provider = try #require(CGDataProvider(data: data as CFData))
    let pdf = try #require(CGPDFDocument(provider))
    #expect(pdf.numberOfPages == 2)
    var decoded: [String] = []
    for number in 1...pdf.numberOfPages {
        let page = try #require(pdf.page(at: number))
        let size = page.getBoxRect(.mediaBox).size
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let image = UIGraphicsImageRenderer(size: CGSize(width: size.width * 2, height: size.height * 2), format: format).image { context in
            UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: size.width * 2, height: size.height * 2))
            context.cgContext.scaleBy(x: 2, y: 2)
            context.cgContext.translateBy(x: 0, y: size.height)
            context.cgContext.scaleBy(x: 1, y: -1)
            context.cgContext.drawPDFPage(page)
        }
        decoded += MakeTestSupport.decodeQRWithCoreImage(try #require(image.pngData()))
    }
    #expect(decoded.sorted() == items.sorted())
}

@Test func batchGenerationFailureDoesNotReturnPartialPdf() {
    #expect(throws: BatchExport.Failure.self) {
        try BatchExport.pdf(items: ["valid", String(repeating: "x", count: 8193)], grid: .grid2x3, paper: .a4)
    }
}
