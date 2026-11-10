import Testing
import Foundation
import QRCore
@testable import QRUI

@Suite struct ResultEventFormatTests {
    let en = Locale(identifier: "en_US")
    let manila = TimeZone(identifier: "Asia/Manila") ?? .gmt

    private func gmt(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 0, _ mi: Int = 0) -> Date {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .gmt
        return c.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: mi)) ?? Date(timeIntervalSince1970: 0)
    }

    @Test func floatingTimeShowsAsWritten() {
        let e = EventInfo(start: gmt(2026, 11, 12, 14, 0), end: gmt(2026, 11, 12, 15, 0), isFloating: true)
        let text = ResultEventFormat.timeText(e, locale: en, timeZone: manila) ?? ""
        #expect(text.contains("2:00") && text.contains("3:00") && text.contains("November 12, 2026"))
    }

    @Test func absoluteTimeShowsInDeviceZone() {
        let e = EventInfo(start: gmt(2026, 11, 12, 14, 0), end: gmt(2026, 11, 12, 15, 0))
        let text = ResultEventFormat.timeText(e, locale: en, timeZone: manila) ?? ""
        #expect(text.contains("10:00") && text.contains("11:00"))
    }

    @Test func allDayEndIsExclusive() {
        let one = EventInfo(start: gmt(2026, 11, 12), end: gmt(2026, 11, 13), isAllDay: true)
        let two = EventInfo(start: gmt(2026, 11, 12), end: gmt(2026, 11, 14), isAllDay: true)
        let oneText = ResultEventFormat.timeText(one, locale: en, timeZone: manila) ?? ""
        let twoText = ResultEventFormat.timeText(two, locale: en, timeZone: manila) ?? ""
        #expect(!oneText.contains("–"))
        #expect(twoText.contains("–") && twoText.contains("November 13"))
    }

    @Test func wallClockBecomesLocalForCalendar() {
        let e = EventInfo(start: gmt(2026, 11, 12, 14, 0), isFloating: true)
        let dates = ResultEventFormat.calendarDates(e, timeZone: manila)
        var c = Calendar(identifier: .gregorian)
        c.timeZone = manila
        let fields = dates.map { c.dateComponents([.hour, .minute, .day], from: $0.start) }
        #expect(fields?.hour == 14 && fields?.minute == 0 && fields?.day == 12)
        #expect(dates?.end.timeIntervalSince(dates?.start ?? .distantPast) == 3600)
    }

    @Test func noStartMeansNoText() {
        #expect(ResultEventFormat.timeText(EventInfo(summary: "x")) == nil)
        #expect(ResultEventFormat.calendarDates(EventInfo(summary: "x")) == nil)
    }
}
