import Testing
import Foundation
@testable import AlarmCore
struct ChallengeTests {
    @Test func memoryRequiresFullOrder() {
        var c = SequenceChallenge(sequence:[2,1,2])
        let first = c.tap(2); #expect(first)
        let wrong = c.tap(0); #expect(!wrong); #expect(c.progress == 0)
        let a = c.tap(2); let b = c.tap(1); #expect(a && b); #expect(!c.complete)
        let last = c.tap(2); #expect(last && c.complete)
        let extra = c.tap(2); #expect(!extra)
    }
    @Test func sustainedAccelerationDoesNotCountRepeatedly() {
        var c = ShakeCounter(); c.sample(acceleration:1.5,timestamp:1); c.sample(acceleration:1.6,timestamp:2); #expect(c.count == 1)
        c.sample(acceleration:0.1,timestamp:2.1); c.sample(acceleration:1.5,timestamp:2.5); #expect(c.count == 2)
        c.sample(acceleration:0,timestamp:2.55); c.sample(acceleration:1.5,timestamp:2.6); #expect(c.count == 2)
        c.sample(acceleration:.nan,timestamp:3); #expect(c.count == 2)
    }
    @Test func colorChecksInk() { #expect(ColorWord.accepts(.blue,ink:.blue)); #expect(!ColorWord.accepts(.red,ink:.blue)) }
    @Test func oldBackupDefaultsDifficulty() throws {
        var r = Routine(); r.mission = .memory
        let bytes = try JSONEncoder().encode(r); let decoded = try JSONDecoder().decode(Routine.self,from:bytes)
        #expect(decoded.difficulty == 1); r.challengeLevel = 4; #expect(throws:(any Error).self) { try r.validate() }
    }
}
