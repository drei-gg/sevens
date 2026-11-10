import Foundation
import Testing
@testable import QRUI

@Suite struct HistoryScreenExportTests {
    @Test func fileNamesUseTheLocalDate() {
        let utc = TimeZone(identifier: "UTC") ?? .gmt
        // 2026-11-10 23:30 UTC
        let date = Date(timeIntervalSince1970: 1_794_353_400)
        #expect(HistoryExportFiles.fileName(format: .json, date: date, timeZone: utc) == "qr-history-2026-11-10.json")
        #expect(HistoryExportFiles.fileName(format: .csv, date: date, timeZone: utc) == "qr-history-2026-11-10.csv")
        let manila = TimeZone(identifier: "Asia/Manila") ?? utc
        #expect(HistoryExportFiles.fileName(format: .csv, date: date, timeZone: manila) == "qr-history-2026-11-11.csv")
    }

    @Test func writeReplacesAnEarlierExportOfTheSameName() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("HistoryScreenExport-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = try HistoryExportFiles.write(Data("one".utf8), named: "qr-history-x.csv", in: dir)
        let url = try HistoryExportFiles.write(Data("two".utf8), named: "qr-history-x.csv", in: dir)
        #expect(try String(contentsOf: url, encoding: .utf8) == "two")
    }
}
