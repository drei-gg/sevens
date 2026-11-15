import Testing
@testable import QRCore

@Suite struct StructuredHostileTests {
    static let all: [@Sendable (String) -> Bool] = [
        { WiFiParser.parse($0) != nil }, { ContactParser.parse($0) != nil }, { EventParser.parse($0) != nil },
        { PhoneParser.parse($0) != nil }, { SMSParser.parse($0) != nil }, { EmailParser.parse($0) != nil },
        { GeoParser.parse($0) != nil },
    ]

    @Test func garbageNeverCrashes() {
        let heads = ["WIFI:", "BEGIN:VCARD\n", "MECARD:", "BEGIN:VEVENT\n", "BEGIN:VCALENDAR\n", "tel:", "sms:", "smsto:",
                     "mailto:", "MATMSG:", "geo:", ""]
        let tails = ["\\", ";;;;;", "=", "N;QUOTED-PRINTABLE:=\n=\n=", ":::", "\"\"\"", "%", "%zz%4", "\r", "\u{0}\u{FEFF}",
                     "DTSTART:99999999999999999999T", "DURATION:P99999999999999999999D", "\u{301};\u{301}:"]
        for h in heads {
            for t in tails {
                for f in Self.all { _ = f(h + t); _ = f(h + String(repeating: t, count: 50)) }
            }
        }
    }

    @Test func hugeInputIsCapped() {
        let big = "BEGIN:VCARD\nNOTE:" + String(repeating: "a", count: 100_000) + "\nEND:VCARD"
        let note = ContactParser.parse(big)?.note
        #expect((note?.utf8.count ?? 0) <= 8192)
        let tail = "WIFI:S:x;" + String(repeating: ";", count: 20_000) + "P:late;;"
        #expect(WiFiParser.parse(tail)?.password == nil)
        let tailCard = "BEGIN:VCARD\n" + String(repeating: "X:y\n", count: 5_000) + "TEL:123\nEND:VCARD"
        #expect(ContactParser.parse(tailCard)?.phones.isEmpty == true)
    }

    @Test func truncatedInputsStillParse() {
        #expect(ContactParser.parse("BEGIN:VCARD\nFN:Ann")?.name == "Ann")
        #expect(EventParser.parse("BEGIN:VEVENT\nSUMMARY:x")?.summary == "x")
    }
}
