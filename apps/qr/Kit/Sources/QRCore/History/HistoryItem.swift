import Foundation

// SPEC §10. One saved scan or made code.

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
    /// the analysis and its embedded analyses (de-duplicated, first seen first).
    public init(analysis: Analysis, kind: Kind, date: Date) {
        let code = analysis.code
        var seen = Set<String>()
        var ids: [String] = []
        for signal in analysis.allSignals where signal.severity != .info {
            if seen.insert(signal.id.rawValue).inserted { ids.append(signal.id.rawValue) }
        }
        if let text = code.text {
            self.init(kind: kind, payload: text, payloadEncoding: .text, symbology: code.symbology,
                      typeID: analysis.payload.typeID, level: analysis.level, signalIDs: ids, date: date)
        } else {
            self.init(kind: kind, payload: Data(code.bytes).base64EncodedString(), payloadEncoding: .base64,
                      symbology: code.symbology, typeID: analysis.payload.typeID, level: analysis.level,
                      signalIDs: ids, date: date)
        }
    }

    /// The `ScannedCode` this item was made from. Bad base64 gives empty bytes and no text.
    public var scannedCode: ScannedCode {
        switch payloadEncoding {
        case .text:
            return ScannedCode(text: payload, symbology: symbology)
        case .base64:
            guard let data = Data(base64Encoded: payload) else {
                return ScannedCode(text: nil, bytes: [], symbology: symbology)
            }
            let bytes = Array(data)
            return ScannedCode(text: String(bytes: bytes, encoding: .utf8), bytes: bytes, symbology: symbology)
        }
    }
}
