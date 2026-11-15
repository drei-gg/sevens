import Foundation

// Document Scanner's logic is the Rust crate apps/scanner/core; its Swift bindings
// (Generated/ScannerCoreFFI.swift) compile into this module. This file keeps the Swift spellings the
// app was written against (`ScanPage()`, `document.validate()`, `page.settings`, `PDFExportPlan`,
// raw values for stored preferences) and only converts: `created` between the core's
// reference-date seconds and `Date`, sets to lists, Int64 to Int. No rule or calculation lives here.

// MARK: documents and pages

extension ScanPage: Identifiable {}
extension ScanDocument: Identifiable {}

public extension ScanPage {
    init(id: UUID = UUID(), text: String = "") { self.init(id: id, text: text, adjustments: nil) }
    /// The adjustments, or the defaults when there are none.
    var settings: PageSettings { scanPageSettings(page: self) }
}

public extension ScanDocument {
    init(id: UUID = UUID(), title: String, pages: [ScanPage], created: Date = Date()) {
        self = newScanDocument(title: title, pages: pages, created: created.timeIntervalSinceReferenceDate)
        self.id = id
    }
    /// `created` as a `Date` (the core keeps seconds since 2001-01-01 UTC, as `Date` does).
    var createdDate: Date {
        get { Date(timeIntervalSinceReferenceDate: created) }
        set { created = newValue.timeIntervalSinceReferenceDate }
    }
    func validate() throws { try documentValidate(document: self) }
    /// Title, note and every page's text, ignoring case and accents. An empty query matches.
    func matches(_ query: String) -> Bool { documentMatches(document: self, query: query) }
}

// The generated ScanError already conforms to LocalizedError, with `errorDescription` defined as
// `String(reflecting: self)` in the generated type, so it can't be redeclared here. Reflection uses
// `description`, so this sentence is what `localizedDescription` shows.
extension ScanError: CustomStringConvertible {
    public var description: String {
        switch self {
        case .InvalidDocument: String(localized: "Something in this document or file isn’t valid, so nothing was changed.")
        case .TooLarge:
            String(localized: "This is more than one document can hold: up to \(scanLimits().maxPages) pages and \(scanLimits().maxDocumentImageBytes / 1_000_000) MB of images.")
        case .MissingPage: String(localized: "A page’s image is missing, so nothing was changed.")
        case .Unreadable: String(localized: "This file couldn’t be read. It may be damaged or from a newer version. Nothing was changed.")
        }
    }
}

public extension PageSettings {
    func validate() throws { try pageSettingsValidate(settings: self) }
}

// MARK: preferences

extension LibraryOrder: CaseIterable {
    public static var allCases: [LibraryOrder] { [.newest, .oldest, .name] }
}

public extension LibraryOrder {
    init?(rawValue: String) {
        guard let order = libraryOrderFromRawValue(raw: rawValue) else { return nil }
        self = order
    }
    var rawValue: String { libraryOrderRawValue(order: self) }
    /// Ties (same date or same title) fall back to the ID, so the order is total.
    func sorted(_ documents: [ScanDocument]) -> [ScanDocument] { librarySorted(order: self, documents: documents) }
}

extension PaperSize: CaseIterable {
    public static var allCases: [PaperSize] { [.a4, .letter] }
}

public extension PaperSize {
    init?(rawValue: String) {
        guard let size = paperSizeFromRawValue(raw: rawValue) else { return nil }
        self = size
    }
    var rawValue: String { paperSizeRawValue(size: self) }
}

// MARK: PDF export

public typealias PDFQuality = PdfQuality
public typealias PDFExportPlan = PdfExportPlan

extension PdfQuality: CaseIterable {
    public static var allCases: [PdfQuality] { [.standard, .compact] }
}

public extension PdfQuality {
    init?(rawValue: String) {
        guard let quality = pdfQualityFromRawValue(raw: rawValue) else { return nil }
        self = quality
    }
    var rawValue: String { pdfQualityRawValue(quality: self) }
}

public extension PdfExportPlan {
    /// The selected pages in document order and a portable file name; throws for an empty
    /// selection or a page that is not in the document.
    init(document: ScanDocument, selected: Set<UUID>) throws {
        self = try pdfExportPlan(document: document, selected: Array(selected))
    }
}
