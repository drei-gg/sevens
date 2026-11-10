import Foundation
import Testing
@testable import QRCore

@Suite struct StructuredRoundTripTests {
    static let awkward = [
        "plain", "semi;colon", "com,ma", "co:lon", #"back\slash"#, #"quo"te"#, "multi\nline", "tab\there",
        "emoji 😀🎉", "日本語のテキスト", "Привет, мир", "ไทย;ภาษา", "%41 %zz", "a&b=c?d#e", "x\\;y\\,z\\\\",
    ]

    @Test func wifi() {
        var cases: [WiFiInfo] = []
        for s in Self.awkward where !s.contains("\n") && !s.contains("\t") {
            cases.append(WiFiInfo(ssid: s, security: "WPA", password: s))
        }
        cases.append(WiFiInfo(ssid: "Open", security: "NOPASS"))
        cases.append(WiFiInfo(ssid: "NoSecurity"))
        cases.append(WiFiInfo(ssid: "deadbeef", security: "WPA", password: "0011aabb", isHidden: true))
        cases.append(WiFiInfo(ssid: "Corp", security: "WPA2-EAP", isHidden: true, eapMethod: "PEAP", identity: "a;b"))
        cases.append(WiFiInfo(ssid: " spaced ", security: "SAE", password: " p "))
        for c in cases {
            let text = PayloadEncoder.encode(.wifi(c))
            #expect(text.hasPrefix("WIFI:"))
            #expect(WiFiParser.parse(text) == c, "\(text)")
        }
        #expect(PayloadEncoder.encode(.wifi(WiFiInfo(ssid: "N", security: "WPA", password: "p", isHidden: true)))
                == "WIFI:T:WPA;S:N;P:p;H:true;;")
        #expect(PayloadEncoder.encode(.wifi(WiFiInfo(ssid: "N", security: "NOPASS"))) == "WIFI:T:nopass;S:N;;")
    }

    @Test func contact() {
        var cases: [ContactInfo] = []
        for s in Self.awkward {
            cases.append(ContactInfo(name: s, givenName: s, familyName: s, organization: s, title: s, phones: [s, "+63 917 1"],
                                     emails: ["a+b@x.org"], urls: ["https://example.com/a,b;c"], addresses: [s + "\nline two"],
                                     note: s + "\nsecond", birthday: "1990-01-02", links: ["https://example.com/a,b;c"]))
        }
        cases.append(ContactInfo(name: "Only Name"))
        cases.append(ContactInfo(name: "Given Family", givenName: "Given", familyName: "Family"))
        cases.append(ContactInfo(name: "Long", note: String(repeating: "日本語😀 long note, ", count: 40) + "end"))
        for c in cases {
            let text = PayloadEncoder.encode(.contact(c))
            #expect(text.hasPrefix("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
            #expect(ContactParser.parse(text) == c, "\(text)")
        }
        // name derived from N when FN isn't given
        let derived = ContactInfo(givenName: "Ann", familyName: "Lee")
        #expect(ContactParser.parse(PayloadEncoder.encode(.contact(derived)))?.name == "Ann Lee")
    }

    @Test func vCardLinesAreFoldedAt75Octets() {
        let c = ContactInfo(name: "Fold", note: String(repeating: "日本語😀é", count: 80))
        let text = PayloadEncoder.encode(.contact(c))
        let lines = text.components(separatedBy: "\r\n")
        #expect(lines.count > 6)
        for l in lines { #expect(l.utf8.count <= 75, "\(l.utf8.count)") }
        #expect(text.contains("\r\n "))
        #expect(!text.replacingOccurrences(of: "\r\n", with: "").contains("\n"))
    }

    @Test func event() {
        func at(_ y: Int, _ mo: Int, _ d: Int, _ h: Int = 0, _ mi: Int = 0, _ s: Int = 0) -> Date {
            ICSDate.wallAsGMT(y, mo, d, h, mi, s)
        }
        var cases: [EventInfo] = []
        for s in Self.awkward {
            cases.append(EventInfo(summary: s, start: at(2026, 11, 10, 9), end: at(2026, 11, 10, 10, 30, 5), location: s,
                                   description: s + "\nmore", url: "https://example.com/e?a=1;b=2", links: ["https://example.com/e?a=1;b=2"]))
        }
        cases.append(EventInfo(summary: "Floating", start: at(2026, 11, 10, 23, 59, 59), isFloating: true))
        cases.append(EventInfo(summary: "All day", start: at(2026, 12, 31), end: at(2027, 1, 1), isAllDay: true))
        cases.append(EventInfo(summary: "Old", start: at(1969, 7, 20, 20, 17), end: at(1969, 7, 21)))
        cases.append(EventInfo(summary: "Leap", start: at(2028, 2, 29, 12)))
        cases.append(EventInfo())
        for c in cases {
            let text = PayloadEncoder.encode(.event(c))
            #expect(text.hasPrefix("BEGIN:VEVENT\r\n"))
            #expect(EventParser.parse(text) == c, "\(text)")
        }
        let utc = PayloadEncoder.encode(.event(EventInfo(start: at(2026, 11, 10, 9))))
        #expect(utc.contains("DTSTART:20261110T090000Z"))
        let allDay = PayloadEncoder.encode(.event(EventInfo(start: at(2026, 11, 10), isAllDay: true)))
        #expect(allDay.contains("DTSTART;VALUE=DATE:20261110"))
        let floating = PayloadEncoder.encode(.event(EventInfo(start: at(2026, 11, 10, 9), isFloating: true)))
        #expect(floating.contains("DTSTART:20261110T090000\r\n"))
    }

    @Test func email() {
        var cases = [EmailInfo(address: "a@example.com"), EmailInfo(address: "a+tag@example.com", subject: "Hi")]
        for s in Self.awkward {
            cases.append(EmailInfo(address: "x@example.com", subject: s, body: s + "\nbody"))
        }
        for c in cases {
            let text = PayloadEncoder.encode(.email(c))
            #expect(text.hasPrefix("mailto:"))
            #expect(EmailParser.parse(text) == c, "\(text)")
        }
        #expect(PayloadEncoder.encode(.email(EmailInfo(address: "a@b.c", subject: "S x", body: "B"))) == "mailto:a@b.c?subject=S%20x&body=B")
    }

    @Test func sms() {
        var cases = [SMSInfo(number: "+639171234567"), SMSInfo(number: "+63 917 123 4567", body: "hello")]
        for s in Self.awkward {
            cases.append(SMSInfo(number: "+1 (555) 010-0100", body: s + "\nnext: 5:30"))
        }
        for c in cases {
            let text = PayloadEncoder.encode(.sms(c))
            #expect(SMSParser.parse(text) == c, "\(text)")
        }
    }

    @Test func phone() {
        for n in ["+639171234567", "+63 (917) 123-4567", "*123#", "0917;ext=5", "1,2", "٣٤٥", "a%b", "x?y"] {
            let text = PayloadEncoder.encode(.phone(n))
            #expect(text.hasPrefix("tel:"))
            #expect(PhoneParser.parse(text) == PhoneInfo(number: n), "\(text)")
        }
    }

    @Test func geo() {
        let cases = [GeoInfo(latitude: "14.5995", longitude: "120.9842"),
                     GeoInfo(latitude: "-33.86", longitude: "151.21", altitude: "30"),
                     GeoInfo(latitude: "0", longitude: "0", query: "Rizal Park"),
                     GeoInfo(latitude: "1.5", longitude: "2.5", altitude: "3", query: "Café & Bar, 日本 😀 (a=b?c)")]
        for c in cases {
            let text = PayloadEncoder.encode(.geo(c))
            #expect(GeoParser.parse(text) == c, "\(text)")
        }
    }

    @Test func linkAndText() {
        #expect(PayloadEncoder.encode(.link("  https://example.com/a b \n")) == "https://example.com/a b")
        #expect(PayloadEncoder.encode(.text(" keep \n me ")) == " keep \n me ")
    }
}
