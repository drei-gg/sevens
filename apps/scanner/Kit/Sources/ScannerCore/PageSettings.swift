import Foundation

/// Original JPEGs are immutable. These settings are applied to previews, OCR and exports.
public struct PageSettings: Codable, Equatable, Sendable {
    public var quarterTurns: Int = 0
    public var brightness: Double = 0
    public var contrast: Double = 1
    public var grayscale: Bool = false
    public init() {}
    public func validate() throws {
        guard (0...3).contains(quarterTurns), brightness.isFinite, (-0.5...0.5).contains(brightness),
              contrast.isFinite, (0.5...2).contains(contrast) else { throw ScanError.invalidDocument }
    }
}
public enum PaperSize: String, CaseIterable, Codable, Sendable { case a4, letter }
public enum LibraryOrder: String, CaseIterable, Sendable {
    case newest, oldest, name
    public func sorted(_ documents: [ScanDocument]) -> [ScanDocument] {
        documents.sorted {
            switch self {
            case .newest: $0.created == $1.created ? $0.id.uuidString < $1.id.uuidString : $0.created > $1.created
            case .oldest: $0.created == $1.created ? $0.id.uuidString < $1.id.uuidString : $0.created < $1.created
            case .name: $0.title == $1.title ? $0.id.uuidString < $1.id.uuidString : $0.title.localizedStandardCompare($1.title) == .orderedAscending
            }
        }
    }
}
public struct LibraryListing: Sendable {
    public var documents: [ScanDocument]
    public var unreadableCount: Int
}
