import Foundation

public enum PDFQuality: String, CaseIterable, Sendable { case standard, compact }
public struct PDFExportPlan: Equatable, Sendable {
    public let pages: [ScanPage]
    public let filename: String
    public init(document: ScanDocument, selected: Set<UUID>) throws {
        pages = document.pages.filter { selected.contains($0.id) }
        guard !pages.isEmpty, selected.isSubset(of: Set(document.pages.map(\.id))) else { throw ScanError.invalidDocument }
        let disallowed = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\:?*<>|\""))
        let cleaned = document.title.components(separatedBy: disallowed).joined(separator: " ")
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        let base = cleaned.isEmpty ? "Scan" : String(cleaned.prefix(100))
        filename = base + (pages.count == document.pages.count ? "" : " - selected pages") + ".pdf"
    }
}
