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

@Test func batchIssuesAreWordedByTheApp() {
    #expect(BatchText.issue(.empty) == "Enter one code’s text or link per line.")
    #expect(BatchText.issue(.lineTooLong(line: 3)) == "Line 3 is too long for a code.")
    #expect(BatchText.issue(.tooManyLines(max: 100)) == "Use at most 100 nonblank lines per batch.")
    #expect(BatchText.issue(.tooLarge(maxKilobytes: 256)) == "The batch is too large. Use at most 256 KB of text.")
    #expect(BatchText.issue(of: batchReview(text: " \n")) == BatchText.issue(.empty))
    #expect(BatchText.issue(of: batchReview(text: "a\nb")) == nil)
    #expect(BatchExport.Failure.code(2).errorDescription == "Code 2 couldn’t be generated. Shorten that line and review again.")
}

@Test func preparedBatchPDFsAreClearedButOtherExportsStay() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("batch-clean-\(UUID().uuidString)", isDirectory: true)
    defer { try? FileManager.default.removeItem(at: dir) }
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let other = dir.appendingPathComponent("qr-code.png")
    try Data([1]).write(to: other)
    let stale = dir.appendingPathComponent("qr-batch-old.pdf")
    try Data([2]).write(to: stale)
    let first = try BatchExport.write(Data([3]), in: dir)
    #expect(!FileManager.default.fileExists(atPath: stale.path))
    let second = try BatchExport.write(Data([4]), in: dir)
    #expect(!FileManager.default.fileExists(atPath: first.path))
    #expect(FileManager.default.fileExists(atPath: second.path))
    BatchExport.discard(second)
    #expect(!FileManager.default.fileExists(atPath: second.path))
    #expect(FileManager.default.fileExists(atPath: other.path))
    BatchExport.removePrepared(in: dir)
    #expect(FileManager.default.fileExists(atPath: other.path))
}
