import Foundation
import Testing
@testable import FastCore

private func instant(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }
private let now = instant("2026-11-11T12:00:00Z")
private func calendar(_ zone: String = "UTC") -> Calendar {
    var value = Calendar(identifier: .gregorian); value.timeZone = TimeZone(identifier: zone)!; value.firstWeekday = 2
    return value
}
private func archive(_ fasts: [FastRecord], plans: [PlannedFast] = []) -> FastArchive {
    var value = FastArchive(); value.fasts = fasts; value.plans = plans; return value
}
private func arcs(_ day: String, _ archive: FastArchive, now: Date = now, zone: String = "UTC") -> [DayArc] {
    HistoryMath.arcs(on: instant(day), archive: archive, calendar: calendar(zone), now: now)
}

@Test func overnightFastLeavesOneDayAtTheTopAndEntersTheNext() {
    let fast = FastRecord(start: instant("2026-11-09T20:00:00Z"), end: instant("2026-11-10T12:00:00Z"))
    let records = archive([fast])
    #expect(arcs("2026-11-09T00:00:00Z", records) == [
        DayArc(fastID: fast.id, kind: .actual, from: 20.0 / 24, to: 1, fromPreviousDay: false, intoNextDay: true)])
    #expect(arcs("2026-11-10T00:00:00Z", records) == [
        DayArc(fastID: fast.id, kind: .actual, from: 0, to: 0.5, fromPreviousDay: true, intoNextDay: false)])
}

@Test func daytimeFastStaysInsideItsDay() {
    let fast = FastRecord(start: instant("2026-11-12T07:00:00Z"), end: instant("2026-11-12T19:00:00Z"))
    #expect(arcs("2026-11-12T00:00:00Z", archive([fast]), now: instant("2026-11-13T00:00:00Z")) == [
        DayArc(fastID: fast.id, kind: .actual, from: 7.0 / 24, to: 19.0 / 24, fromPreviousDay: false, intoNextDay: false)])
}

@Test func endingExactlyAtMidnightDoesNotPassIt() {
    let fast = FastRecord(start: instant("2026-11-09T20:00:00Z"), end: instant("2026-11-10T00:00:00Z"))
    let records = archive([fast])
    #expect(arcs("2026-11-09T00:00:00Z", records).map(\.to) == [1])
    #expect(arcs("2026-11-09T00:00:00Z", records).map(\.intoNextDay) == [false])
    #expect(arcs("2026-11-10T00:00:00Z", records).isEmpty)
}

@Test func aDayInsideALongFastIsAWholeRingLinkedBothWays() {
    let fast = FastRecord(start: instant("2026-11-05T21:00:00Z"), end: instant("2026-11-07T09:00:00Z"))
    #expect(arcs("2026-11-06T00:00:00Z", archive([fast])) == [
        DayArc(fastID: fast.id, kind: .actual, from: 0, to: 1, fromPreviousDay: true, intoNextDay: true)])
}

@Test func backToBackFastsShareADayAsTwoSeparatePieces() {
    let first = FastRecord(start: instant("2026-11-09T20:00:00Z"), end: instant("2026-11-10T12:00:00Z"))
    let second = FastRecord(start: instant("2026-11-10T20:00:00Z"), end: instant("2026-11-11T12:00:00Z"))
    let tuesday = arcs("2026-11-10T00:00:00Z", archive([second, first]))
    #expect(tuesday.map(\.fastID) == [first.id, second.id])
    #expect(tuesday.map(\.fromPreviousDay) == [true, false])
    #expect(tuesday.map(\.intoNextDay) == [false, true])
}

@Test func runningFastEndsAtNowAndLinksOnlyThePastMidnight() {
    let running = FastRecord(start: instant("2026-11-10T20:00:00Z"))
    let records = archive([running])
    let current = instant("2026-11-11T10:00:00Z")
    #expect(arcs("2026-11-10T00:00:00Z", records, now: current).map(\.intoNextDay) == [true])
    #expect(arcs("2026-11-11T00:00:00Z", records, now: current) == [
        DayArc(fastID: running.id, kind: .running, from: 0, to: 10.0 / 24, fromPreviousDay: true, intoNextDay: false)])
}

@Test func onlyUpcomingPlansWithAGoalAreDrawn() {
    let ahead = PlannedFast(start: instant("2026-11-12T20:00:00Z"), goalMinutes: 960)
    let openEnded = PlannedFast(start: instant("2026-11-12T08:00:00Z"), goalMinutes: nil)
    let past = PlannedFast(start: instant("2026-11-10T20:00:00Z"), goalMinutes: 960)
    let records = archive([], plans: [ahead, openEnded, past])
    #expect(arcs("2026-11-12T00:00:00Z", records).map(\.fastID) == [ahead.id])
    #expect(arcs("2026-11-12T00:00:00Z", records).map(\.kind) == [.planned])
    #expect(arcs("2026-11-13T00:00:00Z", records).map(\.to) == [0.5])
    #expect(arcs("2026-11-10T00:00:00Z", records).isEmpty)
}

@Test func noonIsAtTheBottomOnADaylightSavingDay() {
    // New York, 8 March 2026: clocks spring forward, so the day is 23 hours long. A fast from
    // 22:00 the evening before to 12:00 local still ends at the bottom of the dial.
    let fast = FastRecord(start: instant("2026-03-08T03:00:00Z"), end: instant("2026-03-08T16:00:00Z"))
    let pieces = arcs("2026-03-08T12:00:00Z", archive([fast]), now: now, zone: "America/New_York")
    #expect(pieces.map(\.to) == [0.5])
    #expect(pieces.map(\.fromPreviousDay) == [true])
}
