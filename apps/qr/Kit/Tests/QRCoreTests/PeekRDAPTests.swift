import Foundation
import Testing
@testable import QRCore

private func rdapSample(_ name: String) throws -> Data {
    let dir = try #require(Fixtures.directory).appendingPathComponent("peek/rdap")
    return try Data(contentsOf: dir.appendingPathComponent(name))
}

private func date(_ y: Int, _ mo: Int, _ d: Int, _ h: Int = 0, _ mi: Int = 0, _ s: Int = 0) -> Date {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "UTC") ?? .current
    return c.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi, second: s)) ?? .distantPast
}

@Suite struct PeekRDAPTests {
    // MARK: response

    @Test func realVerisignCom() throws {
        #expect(RDAPResponse.registrationDate(json: try rdapSample("com.json")) == date(1997, 9, 15, 4))
    }

    @Test func realPIROrgWithFractionalSeconds() throws {
        let d = try #require(RDAPResponse.registrationDate(json: try rdapSample("org.json")))
        #expect(abs(d.timeIntervalSince(date(2001, 1, 13, 0, 12, 14)) - 0.754) < 0.001)
    }

    @Test func realGoogleRegistryDev() throws {
        let d = try #require(RDAPResponse.registrationDate(json: try rdapSample("dev.json")))
        #expect(abs(d.timeIntervalSince(date(2018, 10, 29, 15, 57, 39)) - 0.435) < 0.001)
    }

    @Test func realNominetUK() throws {
        #expect(RDAPResponse.registrationDate(json: try rdapSample("uk.json")) == date(1994, 12, 13, 3, 49, 48))
    }

    @Test func notFoundBodyHasNoDate() throws {
        #expect(RDAPResponse.registrationDate(json: try rdapSample("404.json")) == nil)
    }

    @Test func offsetsAndOddForms() {
        func reg(_ s: String) -> Date? {
            RDAPResponse.registrationDate(json: Data(#"{"events":[{"eventAction":"Registration","eventDate":"\#(s)"}]}"#.utf8))
        }
        #expect(reg("2020-03-04T05:06:07+02:00") == date(2020, 3, 4, 3, 6, 7))
        #expect(reg("2020-03-04T05:06:07-0530") == date(2020, 3, 4, 10, 36, 7))
        #expect(reg("2020-03-04T05:06:07.123456789Z") != nil)
        #expect(reg("2020-03-04T05:06:07z") == date(2020, 3, 4, 5, 6, 7))
        #expect(reg("2020-02-30T00:00:00Z") == nil)
        #expect(reg("2020-03-04T05:06:07") == nil)
        #expect(reg("yesterday") == nil)
        #expect(reg("") == nil)
    }

    @Test func skipsOtherEventsAndBadShapes() {
        #expect(RDAPResponse.registrationDate(json: Data(#"{"events":[{"eventAction":"expiration","eventDate":"2030-01-01T00:00:00Z"}]}"#.utf8)) == nil)
        #expect(RDAPResponse.registrationDate(json: Data(#"{"events":[{"eventAction":"registration"},{"eventAction":"registration","eventDate":"2010-01-01T00:00:00Z"}]}"#.utf8)) == date(2010, 1, 1))
        for bad in ["", "[]", "{}", "null", #"{"events":"x"}"#, #"{"events":[1,null,"a"]}"#, "{{{", #"{"events":[{"eventAction":5,"eventDate":5}]}"#] {
            #expect(RDAPResponse.registrationDate(json: Data(bad.utf8)) == nil, "\(bad)")
        }
    }

    @Test func oversizedBodyIsIgnored() {
        let pad = String(repeating: "x", count: 300 * 1024)
        let json = #"{"pad":"\#(pad)","events":[{"eventAction":"registration","eventDate":"2010-01-01T00:00:00Z"}]}"#
        #expect(RDAPResponse.registrationDate(json: Data(json.utf8)) == nil)
    }

    // MARK: bootstrap

    private static let sample = Data("""
    {"description":"x","publication":"2026-01-01T00:00:00Z","services":[
      [["com","net"],["http://rdap.example.com/","https://rdap.example.com/v1"]],
      [["ORG"],["http://only-http.example/"]],
      [["uk"],["https://rdap.nominet.example/uk/"]],
      [["xn--p1ai"],["https://rdap.cctld.example/"]],
      [["com"],["https://dup.example/"]],
      [[],[]], ["junk"], 5
    ]}
    """.utf8)

    @Test func bootstrapPrefersHTTPSAndAddsSlash() {
        let b = RDAPBootstrap(json: Self.sample)
        #expect(b.baseURL(forTLD: "com") == "https://rdap.example.com/v1/")
        #expect(b.baseURL(forTLD: "net") == "https://rdap.example.com/v1/")
        #expect(b.baseURL(forTLD: "org") == "http://only-http.example/")
        #expect(b.baseURL(forTLD: "COM") == "https://rdap.example.com/v1/")
        #expect(b.baseURL(forTLD: "zzz") == nil)
    }

    @Test func bootstrapQueryURLUsesLastLabel() {
        let b = RDAPBootstrap(json: Self.sample)
        #expect(b.queryURL(forDomain: "example.com") == "https://rdap.example.com/v1/domain/example.com")
        #expect(b.queryURL(forDomain: "Shop.Example.CO.UK") == "https://rdap.nominet.example/uk/domain/shop.example.co.uk")
        #expect(b.queryURL(forDomain: "xn--80aswg.xn--p1ai") == "https://rdap.cctld.example/domain/xn--80aswg.xn--p1ai")
        #expect(b.queryURL(forDomain: "example.zzz") == nil)
        #expect(b.queryURL(forDomain: "") == nil)
        #expect(b.queryURL(forDomain: "com") != nil)
        for bad in ["exa mple.com", "example.com/x", "münchen.com", "example.com.", "a.com?x=1", "ex@mple.com"] {
            #expect(b.queryURL(forDomain: bad) == nil, "\(bad)")
        }
    }

    @Test func badBootstrapJSONKnowsNothing() {
        for bad in ["", "{}", "[]", "nonsense", #"{"services":5}"#, #"{"services":[[["com"]]]}"#] {
            let b = RDAPBootstrap(json: Data(bad.utf8))
            #expect(b.baseURL(forTLD: "com") == nil)
            #expect(b.queryURL(forDomain: "example.com") == nil)
        }
    }

    @Test func shippedIANAFileParses() throws {
        // The data file lives in Sources; it is only reachable when the tests run from a checkout.
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../Sources/QRCore/Data/rdap-dns.json").standardized
        guard let data = try? Data(contentsOf: file) else { return }
        let b = RDAPBootstrap(json: data)
        for tld in ["com", "org", "net", "uk", "dev", "app", "xyz"] {
            let base = try #require(b.baseURL(forTLD: tld), "\(tld)")
            #expect(base.hasPrefix("https://") && base.hasSuffix("/"), "\(tld) \(base)")
        }
        #expect(b.queryURL(forDomain: "example.com") == "https://rdap.verisign.com/com/v1/domain/example.com")
    }
}

@Suite struct PeekDomainAgeTests {
    @Test func wholeDaysFloorNeverNegative() {
        let t = Date(timeIntervalSince1970: 1_700_000_000)
        #expect(DomainAge.days(registeredAt: t, now: t) == 0)
        #expect(DomainAge.days(registeredAt: t, now: t.addingTimeInterval(86_399)) == 0)
        #expect(DomainAge.days(registeredAt: t, now: t.addingTimeInterval(86_400)) == 1)
        #expect(DomainAge.days(registeredAt: t, now: t.addingTimeInterval(86_400 * 30 + 3600)) == 30)
        #expect(DomainAge.days(registeredAt: t, now: t.addingTimeInterval(-5 * 86_400)) == 0)
        #expect(DomainAge.days(registeredAt: .distantPast, now: .distantFuture) > 1_000_000)
        #expect(DomainAge.days(registeredAt: Date(timeIntervalSince1970: .nan), now: t) == 0)
    }
}
