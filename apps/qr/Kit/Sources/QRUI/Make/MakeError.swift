import Foundation

/// Why `CodeGenerator` could not produce a code. `message` is the inline text under the field.
public enum MakeError: Error, Sendable, Hashable {
    case empty
    case tooLong
    case notASCII
    case generatorFailed

    public var message: String {
        switch self {
        case .empty:
            return String(localized: "make.error.empty", defaultValue: "Enter something to turn into a code.", bundle: .module)
        case .tooLong:
            return String(localized: "make.error.tooLong", defaultValue: "This is too long for the chosen code type. Shorten it or pick another type.", bundle: .module)
        case .notASCII:
            return String(localized: "make.error.notASCII", defaultValue: "Code 128 only supports plain ASCII characters. Remove accents and symbols, or pick another type.", bundle: .module)
        case .generatorFailed:
            return String(localized: "make.error.generatorFailed", defaultValue: "The code could not be created. Try different content.", bundle: .module)
        }
    }
}

extension MakeError: LocalizedError {
    public var errorDescription: String? { message }
}
