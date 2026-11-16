import XCTest
import PDFKit
@testable import BloomUI

final class ReportTests: XCTestCase {
    @MainActor func testLongReportKeepsItsLastPageAndUnicode() throws {
        let text = "Cycle & Health Report\nTemperature: 36.75 °C\n" + (0..<400).map { "Day \($0): a detailed observation with enough words to require pagination." }.joined(separator: "\n") + "\nFINAL-REPORT-SENTINEL"
        let data = reportPDF(text)
        let pdf = try XCTUnwrap(PDFDocument(data: data))
        XCTAssertGreaterThan(pdf.pageCount, 2)
        let extracted = try XCTUnwrap(pdf.string)
        XCTAssertTrue(extracted.contains("36.75 °C"))
        XCTAssertTrue(extracted.contains("FINAL-REPORT-SENTINEL"))
    }
}
