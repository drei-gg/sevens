import Foundation

public enum HistoryExport {
    /// The file's own shape.
    public static func json(_ items: [HistoryItem]) throws -> Data { try HistoryFile.encode(items) }

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
