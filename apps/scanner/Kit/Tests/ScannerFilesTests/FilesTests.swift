import Foundation
import Testing
import ScannerFiles

private func temporaryFolder() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

@Test func writesReadBackAndReportTheirSize() throws {
    let folder = try temporaryFolder(); defer { try? FileManager.default.removeItem(at: folder) }
    let file = folder.appendingPathComponent("page.jpg")
    try ScanFiles.write(Data([1, 2, 3]), to: file)
    try ScanFiles.write(Data([4, 5]), to: file)
    #expect(try Data(contentsOf: file) == Data([4, 5]))
    #expect(try ScanFiles.size(file) == 2)
}

@Test func exactReadsRefuseAShortFile() throws {
    let folder = try temporaryFolder(); defer { try? FileManager.default.removeItem(at: folder) }
    let file = folder.appendingPathComponent("short")
    try Data([1, 2, 3]).write(to: file)
    let handle = try FileHandle(forReadingFrom: file); defer { try? handle.close() }
    #expect(try ScanFiles.read(handle, count: 2) == Data([1, 2]))
    #expect(try ScanFiles.read(handle, count: 0) == Data())
    #expect(throws: ScanFileError.shortRead) { try ScanFiles.read(handle, count: 2) }
}

@Test func copiesStreamInChunksAndStopAtTheCount() throws {
    let folder = try temporaryFolder(); defer { try? FileManager.default.removeItem(at: folder) }
    let source = folder.appendingPathComponent("source"), target = folder.appendingPathComponent("target")
    let bytes = Data((0..<(ScanFiles.chunkBytes * 2 + 10)).map { UInt8(truncatingIfNeeded: $0) })
    try (bytes + Data([0xff])).write(to: source)
    let input = try FileHandle(forReadingFrom: source); defer { try? input.close() }
    let output = try ScanFiles.create(target)
    var seen: [Int] = []; var streamed = Data()
    try ScanFiles.copy(input, to: output, count: bytes.count) { seen.append($0.count); streamed += $0 }
    try output.close()
    #expect(seen == [ScanFiles.chunkBytes, ScanFiles.chunkBytes, 10])
    #expect(streamed == bytes)
    #expect(try Data(contentsOf: target) == bytes)
    #expect(throws: ScanFileError.shortRead) { try ScanFiles.copy(input, to: FileHandle.nullDevice, count: 2) { _ in } }
}
