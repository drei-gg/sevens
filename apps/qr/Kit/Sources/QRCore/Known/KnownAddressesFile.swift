import Foundation

/// The addresses-you-know file (SPEC §4.4): `{ "schema": 1, "entries": [{ "domain", "added" }] }`,
/// dates ISO 8601. The same `entries` array is what History's JSON export carries under
/// `knownAddresses`.
public enum KnownAddressesFile {
    public static let schema = 1
    public static let fileName = "known.v1.json"

    public enum Decoded: Sendable, Equatable {
        case addresses(KnownAddresses)
        /// Leave the file untouched; this version can't read it.
        case unknownSchema(Int)
        case unreadable
    }

    private struct Head: Decodable { var schema: Int }
    private struct Body: Decodable { var entries: [LossyEntry] }
    struct LossyEntry: Decodable {
        var entry: KnownAddresses.Entry?
        init(from decoder: any Decoder) throws { entry = try? KnownAddresses.Entry(from: decoder) }
    }
    private struct Envelope: Encodable {
        var schema: Int
        var entries: [KnownAddresses.Entry]
    }

    static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let date = HistoryDates.parse(text) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad date"))
        }
        return decoder
    }

    static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }

    public static func decode(_ data: Data) -> Decoded {
        let decoder = decoder()
        guard let head = try? decoder.decode(Head.self, from: data) else { return .unreadable }
        guard head.schema == schema else { return .unknownSchema(head.schema) }
        guard let body = try? decoder.decode(Body.self, from: data) else { return .unreadable }
        return .addresses(from(entries: body.entries.compactMap(\.entry)))
    }

    public static func encode(_ known: KnownAddresses) throws -> Data {
        try encoder().encode(Envelope(schema: schema, entries: known.entries))
    }

    /// Rebuilds a list from stored entries: normalized, no duplicates, newest first.
    static func from(entries: [KnownAddresses.Entry]) -> KnownAddresses {
        var result = KnownAddresses()
        for entry in entries.sorted(by: { $0.added < $1.added }) { result.add(entry.domain, at: entry.added) }
        return result
    }
}
