import Foundation
import QRCore

/// One code found in a shared image, analysed.
struct ExtensionCheckCode: Identifiable, Sendable {
    let id = UUID()
    let analysis: Analysis
    var summary: ExtensionSummary? { ExtensionSummary.main(for: analysis.payload) }
    /// What Copy puts on the pasteboard: the raw payload text. Nil for non-text codes.
    var copyText: String? { analysis.code.text }
}

/// What came out of one shared image.
struct ExtensionCheckImage: Identifiable, Sendable {
    let id = UUID()
    let position: Int
    let codes: [ExtensionCheckCode]
    /// The image could not be read at all.
    let unreadable: Bool
}

enum ExtensionCheckRules {
    static let maxImages = 5
    /// Cap on codes listed per image, to bound the view.
    static let maxCodesPerImage = 10
    /// Largest image file we load, bytes (extension memory limit).
    static let maxImageBytes = 30 * 1024 * 1024
}
