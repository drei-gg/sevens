import Foundation

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

    private struct Head: Decodable { var schema: Int }

    private struct Lossy: Decodable {
        var item: HistoryItem?
        init(from decoder: any Decoder) throws {
            item = try? HistoryItem(from: decoder)
        }
    }

    private struct Body: Decodable { var items: [Lossy] }

    private struct Envelope: Encodable {
        var schema: Int
        var items: [HistoryItem]
    }

    /// `{ "schema": 1, "items": [...] }`, dates ISO 8601 (whole seconds out; fractional seconds also read).
    public static func decode(_ data: Data) -> Decoded {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let date = HistoryDates.parse(text) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad date"))
        }
        guard let head = try? decoder.decode(Head.self, from: data) else { return .unreadable }
        guard head.schema == schema else { return .unknownSchema(head.schema) }
        guard let body = try? decoder.decode(Body.self, from: data) else { return .unreadable }
        return .items(body.items.compactMap(\.item))
    }

    public static func encode(_ items: [HistoryItem]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(Envelope(schema: schema, items: items))
    }

    /// Case-insensitive substring over payload, note and type ID; newest first.
    public static func search(_ items: [HistoryItem], query: String) -> [HistoryItem] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let ordered = items.enumerated().sorted { a, b in
            a.element.date != b.element.date ? a.element.date > b.element.date : a.offset < b.offset
        }.map(\.element)
        if needle.isEmpty { return ordered }
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        return ordered.filter { item in
            item.payload.range(of: needle, options: options) != nil
                || item.typeID.range(of: needle, options: options) != nil
                || (item.note?.range(of: needle, options: options) != nil)
        }
    }
}

enum HistoryDates {
    static func parse(_ text: String) -> Date? {
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        if let d = plain.date(from: text) { return d }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: text)
    }

    static func format(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: date)
    }
}
