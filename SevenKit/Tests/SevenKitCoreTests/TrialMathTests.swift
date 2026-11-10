import Foundation
import Testing
import SevenKitCore

@Suite struct TrialMathTests {
    let start = Date(timeIntervalSince1970: 1_800_000_000)
    let day: TimeInterval = 86_400

    @Test func dayNumberIsOneBasedAndClamped() {
        #expect(TrialMath.dayNumber(startedAt: start, now: start) == 1)
        #expect(TrialMath.dayNumber(startedAt: start, now: start.addingTimeInterval(day - 1)) == 1)
        #expect(TrialMath.dayNumber(startedAt: start, now: start.addingTimeInterval(day)) == 2)
        #expect(TrialMath.dayNumber(startedAt: start, now: start.addingTimeInterval(6 * day)) == 7)
        #expect(TrialMath.dayNumber(startedAt: start, now: start.addingTimeInterval(30 * day)) == 7)
        #expect(TrialMath.dayNumber(startedAt: start, now: start.addingTimeInterval(-5 * day)) == 1)
    }

    @Test func daysRemainingRoundsUpAndStopsAtZero() {
        let end = start.addingTimeInterval(7 * day)
        #expect(TrialMath.daysRemaining(endsAt: end, now: start) == 7)
        #expect(TrialMath.daysRemaining(endsAt: end, now: end.addingTimeInterval(-1)) == 1)
        #expect(TrialMath.daysRemaining(endsAt: end, now: end) == 0)
        #expect(TrialMath.daysRemaining(endsAt: end, now: end.addingTimeInterval(day)) == 0)
    }

    @Test func totalDays() {
        #expect(TrialMath.totalDays(7 * day) == 7)
        #expect(TrialMath.totalDays(10) == 1)
    }
}
