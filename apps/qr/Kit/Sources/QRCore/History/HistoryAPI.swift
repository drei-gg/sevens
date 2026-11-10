import Foundation

// WP 1.8 owns this folder (and may split this file). The signatures below are what
// QRUI (WP 2.5, 3.4) calls; keep them. PLAN §6.9.

public struct HistoryItem: Codable, Sendable, Hashable, Identifiable {
    public enum Kind: String, Codable, Sendable { case scanned, made }
    public enum PayloadEncoding: String, Codable, Sendable { case text, base64 }

    public var id: UUID
    public var kind: Kind
    /// Raw text, or base64 of the bytes for binary codes.
    public var payload: String
    public var payloadEncoding: PayloadEncoding
    public var symbology: Symbology
    public var typeID: String
    public var level: Level
    /// `SignalID` raw values (strings, so a newer file with unknown IDs still decodes).
    public var signalIDs: [String]
    public var date: Date
    public var note: String?

    public init(id: UUID = UUID(), kind: Kind, payload: String, payloadEncoding: PayloadEncoding = .text,
                symbology: Symbology, typeID: String, level: Level, signalIDs: [String], date: Date, note: String? = nil) {
        self.id = id
        self.kind = kind
        self.payload = payload
        self.payloadEncoding = payloadEncoding
        self.symbology = symbology
        self.typeID = typeID
        self.level = level
        self.signalIDs = signalIDs
        self.date = date
        self.note = note
    }

    /// From an analysis: text payloads as text, others as base64; non-info signal IDs of
    /// the analysis and its embedded analyses.
    public init(analysis: Analysis, kind: Kind, date: Date) { fatalError("WP 1.8") }

    /// The `ScannedCode` this item was made from.
    public var scannedCode: ScannedCode { fatalError("WP 1.8") }
}

public enum HistoryFile {
    public static let schema = 1
    public static let fileName = "history.v1.json"

    public enum Decoded: Sendable, Equatable {
        case items([HistoryItem])
        /// Leave the file untouched; show an empty history and say this version can't read it.
        case unknownSchema(Int)
        /// Not JSON or not our shape. Treated like unknownSchema (never overwritten).
        case unreadable
    }

    /// `{ "schema": 1, "items": [...] }`, dates ISO 8601.
    public static func decode(_ data: Data) -> Decoded { fatalError("WP 1.8") }
    public static func encode(_ items: [HistoryItem]) throws -> Data { fatalError("WP 1.8") }
    /// Case-insensitive substring over payload, note and type ID; newest first.
    public static func search(_ items: [HistoryItem], query: String) -> [HistoryItem] { fatalError("WP 1.8") }
}

public enum HistoryExport {
    /// The file's own shape.
    public static func json(_ items: [HistoryItem]) throws -> Data { fatalError("WP 1.8") }
    /// RFC 4180; a cell starting with `= + - @` gets a leading `'`.
    public static func csv(_ items: [HistoryItem]) -> String { fatalError("WP 1.8") }
}
