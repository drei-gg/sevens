import Foundation
import Testing
@testable import FastCore

@Test func backupOfFractionalTimestampsMergesBackIntoItsSource() throws {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    for fraction in [0.1234567, 0.9876543, 0.0000001, 0.9999999] {
        var archive = FastArchive()
        archive.fasts = [FastRecord(start: now.addingTimeInterval(-7_200 + fraction), end: now.addingTimeInterval(-3_600 + fraction))]
        archive.journal = [JournalEntry(date: now.addingTimeInterval(-3_000 + fraction), text: "Keep precise times")]
        let copy = try ArchiveCodec.decode(ArchiveCodec.encode(archive), now: now)
        #expect(try ArchiveCodec.merge(copy, into: archive, now: now).totalAdded == 0)
        var edited = copy
        edited.fasts[0].start.addTimeInterval(0.01)
        #expect(throws: TrackerError.conflict) { try ArchiveCodec.merge(edited, into: archive, now: now) }
        edited = copy; edited.fasts[0].reflection = "An actual edit"
        #expect(throws: TrackerError.conflict) { try ArchiveCodec.merge(edited, into: archive, now: now) }
    }
}
