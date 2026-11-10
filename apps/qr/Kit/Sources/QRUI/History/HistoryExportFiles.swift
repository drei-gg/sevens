import Foundation

/// Export file naming and the temporary file the share sheet hands out (PLAN §6.9).
enum HistoryExportFiles {
    enum Format: Sendable, Equatable {
        case json, csv
        var fileExtension: String { self == .json ? "json" : "csv" }
    }

    /// `qr-history-2026-11-10.json`: the local calendar date, always ASCII digits.
    static func fileName(format: Format, date: Date, timeZone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        let stamp = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
        return "qr-history-\(stamp).\(format.fileExtension)"
    }

    /// Writes `data` to `directory/name`, replacing an older export of the same day, with
    /// complete file protection (History is private).
    static func write(_ data: Data, named name: String,
                      in directory: URL = FileManager.default.temporaryDirectory) throws -> URL {
        let url = directory.appendingPathComponent(name)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }
}
