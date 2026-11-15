import Foundation
import Testing
@testable import AlarmCore

struct DefaultsTests {
    @Test func archivesWithoutDefaultsRemainReadable() throws {
        let bytes = try BackupCodec.encode(LoopArchive())
        var object = try #require(JSONSerialization.jsonObject(with:bytes) as? [String:Any])
        object.removeValue(forKey:"alarmDefaults")
        let archive = try BackupCodec.decode(JSONSerialization.data(withJSONObject:object))
        #expect(archive.alarmDefaults == nil)
        #expect((archive.alarmDefaults ?? AlarmDefaults()).routine().snoozeMinutes == 5)
    }
    @Test func defaultsRoundTripAndRejectInvalidBackupValues() throws {
        var archive = LoopArchive(); var defaults = AlarmDefaults()
        defaults.tone = .pulse; defaults.snoozeMinutes = 12; defaults.snoozeLimit = 0; defaults.followUpMinutes = 7
        archive.alarmDefaults = defaults
        #expect(try BackupCodec.decode(BackupCodec.encode(archive)).alarmDefaults == defaults)
        let loop = defaults.routine(kind:.loop)
        #expect(loop.delivery == .reminder); #expect(loop.followUpMinutes == 0)
        try loop.validate()
        defaults.snoozeMinutes = 0; archive.alarmDefaults = defaults
        #expect(throws:(any Error).self) { try BackupCodec.decode(BackupCodec.encode(archive)) }
    }
}
