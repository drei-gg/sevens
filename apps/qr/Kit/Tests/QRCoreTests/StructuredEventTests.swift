import Foundation
import Testing
@testable import QRCore

@Suite struct StructuredEventTests {
    @Test func utc() {
        let e = EventParser.parse("BEGIN:VEVENT\nDTSTART:20261110T090000Z\nEND:VEVENT")
        #expect(e?.start == Date(timeIntervalSince1970: 1_794_301_200))
        #expect(e?.isFloating == false)
        #expect(e?.isAllDay == false)
        #expect(e?.timeZoneID == nil)
    }

    @Test func tzidKnownAndUnknown() {
        let manila = EventParser.parse("BEGIN:VEVENT\nDTSTART;TZID=Asia/Manila:20261110T090000\nEND:VEVENT")
        #expect(manila?.start == Date(timeIntervalSince1970: 1_794_301_200 - 8 * 3600))
        #expect(manila?.timeZoneID == "Asia/Manila")
        let odd = EventParser.parse("BEGIN:VEVENT\nDTSTART;TZID=Nowhere/Land:20261110T090000\nEND:VEVENT")
        #expect(odd?.isFloating == true)
        #expect(odd?.timeZoneID == nil)
        #expect(odd?.start == Date(timeIntervalSince1970: 1_794_301_200))
    }

    @Test func allDayAndDurations() {
        let a = EventParser.parse("BEGIN:VEVENT\nDTSTART:20261110\nEND:VEVENT")
        #expect(a?.isAllDay == true)
        #expect(a?.start == Date(timeIntervalSince1970: 1_794_268_800))
        let d = EventParser.parse("BEGIN:VEVENT\nDTSTART:20261110T090000Z\nDURATION:P1DT2H30M15S\nEND:VEVENT")
        #expect(d?.end == Date(timeIntervalSince1970: 1_794_301_200 + 86_400 + 9_000 + 15))
        let w = EventParser.parse("BEGIN:VEVENT\nDTSTART:20261110T090000Z\nDURATION:P2W\nEND:VEVENT")
        #expect(w?.end == Date(timeIntervalSince1970: 1_794_301_200 + 14 * 86_400))
        let bad = EventParser.parse("BEGIN:VEVENT\nDTSTART:20261110T090000Z\nDURATION:PT\nEND:VEVENT")
        #expect(bad?.end == nil)
    }

    @Test func invalidDates() {
        #expect(EventParser.parse("BEGIN:VEVENT\nDTSTART:20261310T090000Z\nEND:VEVENT")?.start == nil)
        #expect(EventParser.parse("BEGIN:VEVENT\nDTSTART:20260230\nEND:VEVENT")?.start == nil)
        #expect(EventParser.parse("BEGIN:VEVENT\nDTSTART:soon\nEND:VEVENT")?.start == nil)
    }

    @Test func calendarWrapperAndFirstEvent() {
        let t = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:One\nURL:x.example\nEND:VEVENT\nBEGIN:VEVENT\nSUMMARY:Two\nEND:VEVENT\nEND:VCALENDAR"
        let e = EventParser.parse(t)
        #expect(e?.summary == "One")
        #expect(e?.links == ["x.example"])
        #expect(EventParser.parse("BEGIN:VCALENDAR\nBEGIN:VTODO\nEND:VTODO\nEND:VCALENDAR") == nil)
        #expect(EventParser.parse("hello") == nil)
    }

    @Test func foldedText() {
        let e = EventParser.parse("begin:vevent\r\nDESCRIPTION:a\\, b\\;\r\n  c\\nd\r\nEND:VEVENT")
        #expect(e?.description == "a, b; c\nd")
    }
}
