import Foundation
import Testing
@testable import QRCore

@Suite struct StructuredFixtureTests {
    static func payload(for c: FixtureCase) -> Payload? {
        let text = c.scannedCode.text ?? ""
        switch c.expect.type {
        case "wifi": return WiFiParser.parse(text).map(Payload.wifi)
        case "contact": return ContactParser.parse(text).map(Payload.contact)
        case "event": return EventParser.parse(text).map(Payload.event)
        case "phone": return PhoneParser.parse(text).map(Payload.phone)
        case "sms": return SMSParser.parse(text).map(Payload.sms)
        case "email": return EmailParser.parse(text).map(Payload.email)
        case "geo": return GeoParser.parse(text).map(Payload.geo)
        default: return nil
        }
    }

    @Test func fixturesParseToExpectedFields() throws {
        let cases = try Fixtures.load("structured.json")
        #expect(cases.count >= 30)
        for c in cases {
            guard let p = Self.payload(for: c) else {
                Issue.record("\(c.id): parser returned nil")
                continue
            }
            #expect(p.typeID == c.expect.type, "\(c.id)")
            for (k, v) in c.expect.fields ?? [:] {
                #expect(p.fields[k] == v, "\(c.id): fields[\(k)] = \(p.fields[k] ?? "nil"), expected \(v)")
            }
        }
    }

    @Test func fixtureIdsAndLevelsFollowTheConvention() throws {
        let prefixes = ["wifi-", "vcard-", "mecard-", "vevent-", "tel-", "sms-", "smsto-", "mailto-", "matmsg-", "geo-"]
        let cases = try Fixtures.load("structured.json")
        #expect(Set(cases.map(\.id)).count == cases.count)
        for c in cases {
            #expect(prefixes.contains { c.id.hasPrefix($0) }, "\(c.id)")
            let open = c.expect.signals == ["openWiFi"]
            #expect(c.expect.level == (open ? 1 : 0), "\(c.id)")
            if let p = Self.payload(for: c), case .wifi(let w) = p {
                #expect(w.isOpen == open, "\(c.id)")
            }
        }
    }
}
