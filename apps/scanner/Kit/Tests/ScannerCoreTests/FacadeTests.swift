import Testing
import Foundation
@testable import ScannerCore

// The façade only converts; the rules themselves are tested in the crate (tests/swift_parity.rs).

@Test func createdConvertsThroughTheReferenceDate() throws {
    let date = Date(timeIntervalSince1970: 1_800_000_000.25)
    var document = ScanDocument(title: "Receipt", pages: [ScanPage(text: "Total")], created: date)
    #expect(document.created == date.timeIntervalSinceReferenceDate && document.createdDate == date)
    document.createdDate = Date(timeIntervalSinceReferenceDate: 0)
    #expect(document.created == 0)
    #expect(document.version == 1 && document.note == nil && document.favorite == nil)
    try document.validate()
    let decoded = try manifestRead(data: manifestEncode(document: document), folderName: document.id.uuidString)
    #expect(decoded == document)
}

@Test func defaultsMatchTheCore() {
    let page = ScanPage()
    #expect(page.text.isEmpty && page.adjustments == nil && page.settings == PageSettings())
    #expect(PageSettings() == pageSettingsDefault())
    #expect(ScanPage(id: page.id).id == page.id)
    var settings = PageSettings(); settings.contrast = .infinity
    #expect(throws: ScanError.InvalidDocument) { try settings.validate() }
    #expect(throws: ScanError.InvalidDocument) { try ScanDocument(title: " ", pages: [page]).validate() }
}

@Test func preferencesKeepTheirStoredRawValues() {
    #expect(LibraryOrder.allCases.map(\.rawValue) == ["newest", "oldest", "name"])
    #expect(LibraryOrder.allCases.allSatisfy { LibraryOrder(rawValue: $0.rawValue) == $0 })
    #expect(LibraryOrder(rawValue: "size") == nil)
    #expect(PaperSize.allCases.map(\.rawValue) == ["a4", "letter"] && PaperSize(rawValue: "letter") == .letter)
    #expect(PDFQuality.allCases.map(\.rawValue) == ["standard", "compact"] && PDFQuality(rawValue: "compact") == .compact)
}

@Test func libraryOrderAndExportPlanCallTheCore() throws {
    let older = ScanDocument(title: "B", pages: [ScanPage()], created: Date(timeIntervalSinceReferenceDate: 1))
    let newer = ScanDocument(title: "a", pages: [ScanPage()], created: Date(timeIntervalSinceReferenceDate: 2))
    #expect(LibraryOrder.newest.sorted([older, newer]) == [newer, older])
    #expect(LibraryOrder.name.sorted([older, newer]) == [newer, older])
    let pages = [ScanPage(), ScanPage()]
    let plan = try PDFExportPlan(document: ScanDocument(title: "Tax: 2026", pages: pages), selected: [pages[1].id])
    #expect(plan.pages == [pages[1]] && plan.filename == "Tax  2026 - selected pages.pdf")
    #expect(throws: ScanError.InvalidDocument) { try PDFExportPlan(document: ScanDocument(title: "T", pages: pages), selected: []) }
}

@Test func everyErrorReadsAsASentence() {
    for error in [ScanError.InvalidDocument, .TooLarge, .MissingPage, .Unreadable] {
        let text = error.errorDescription ?? ""
        #expect(!text.isEmpty && !text.contains("ScanError") && !text.contains("ScannerCore"))
        #expect(text.hasSuffix(".") && !text.contains("—"))
    }
    #expect(ScanError.TooLarge.errorDescription?.contains("50 pages") == true)
}
