import Foundation
import QRCore

/// What the Make extension found in the shared items (SPEC §9), before any decision.
enum ExtensionMakeSource: Equatable, Sendable {
    /// A web URL attachment (a Maps URL arrives like this and stays a link).
    case webURL(String)
    /// A vCard file's contents.
    case vCard(String)
    /// Plain text.
    case text(String)
    /// A file that is not a vCard and not text.
    case otherFile
}

enum ExtensionMakeDecision: Equatable, Sendable {
    case request(MakeRequest)
    /// "Can't make a code from this file".
    case unsupported
}

/// Pure mapping from shared content to a `MakeRequest`.
enum ExtensionMakeInput {
    /// Longest text we keep from an attachment (no QR holds more than 2953 bytes; the generator
    /// reports `tooLong` past that).
    static let maxTextBytes = 64 * 1024

    static func decide(_ source: ExtensionMakeSource?) -> ExtensionMakeDecision {
        switch source {
        case .none, .otherFile:
            return .unsupported
        case .webURL(let url):
            let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? .unsupported : .request(.link(trimmed))
        case .vCard(let contents):
            let trimmed = contents.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return .unsupported }
            if let contact = ContactParser.parse(trimmed) { return .request(.contact(contact)) }
            return .request(.text(trimmed))
        case .text(let text):
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? .unsupported : .request(.text(trimmed))
        }
    }
}
