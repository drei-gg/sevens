import Foundation

public enum HistoryExport {
    /// The file's own shape. With `known`, the same envelope plus a top-level `knownAddresses`
    /// array (SPEC §10); readers that only know `schema` and `items` ignore it, so older exports
    /// and importers stay compatible. CSV doesn't carry the known addresses.
    public static func json(_ items: [HistoryItem], known: KnownAddresses? = nil) throws -> Data {
        guard let known else { return try HistoryFile.encode(items) }
        struct Envelope: Encodable {
            var schema: Int
            var items: [HistoryItem]
            var knownAddresses: [KnownAddresses.Entry]
        }
        return try KnownAddressesFile.encoder().encode(
            Envelope(schema: HistoryFile.schema, items: items, knownAddresses: known.entries))
    }

    /// The addresses an export carries; empty for an export without them.
    public static func knownAddresses(in data: Data) -> KnownAddresses {
        struct Body: Decodable { var knownAddresses: [KnownAddressesFile.LossyEntry]? }
        guard let body = try? KnownAddressesFile.decoder().decode(Body.self, from: data) else { return KnownAddresses() }
        return KnownAddressesFile.from(entries: (body.knownAddresses ?? []).compactMap(\.entry))
    }

    /// RFC 4180 (CRLF); a cell starting with `= + - @` gets a leading `'`.
    public static func csv(_ items: [HistoryItem]) -> String {
        var rows = ["date,kind,type,level,payload,note,signals,symbology,id"]
        for item in items {
            let cells = [
                HistoryDates.format(item.date),
                item.kind.rawValue,
                item.typeID,
                levelWord(item.level),
                item.payload,
                item.note ?? "",
                item.signalIDs.joined(separator: " "),
                item.symbology.rawValue,
                item.id.uuidString,
            ]
            rows.append(cells.map(cell).joined(separator: ","))
        }
        return rows.joined(separator: "\r\n") + "\r\n"
    }

    private static func levelWord(_ level: Level) -> String {
        switch level {
        case .normal: return "normal"
        case .careful: return "careful"
        case .likelyScam: return "likelyScam"
        }
    }

    private static func cell(_ raw: String) -> String {
        var text = raw
        if let first = text.unicodeScalars.first, "=+-@".unicodeScalars.contains(first) { text = "'" + text }
        let needsQuotes = text.unicodeScalars.contains { $0 == "," || $0 == "\"" || $0 == "\r" || $0 == "\n" }
        guard needsQuotes else { return text }
        return "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
}
