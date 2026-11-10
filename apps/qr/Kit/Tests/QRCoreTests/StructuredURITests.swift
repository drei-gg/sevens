import Testing
@testable import QRCore

@Suite struct StructuredURITests {
    @Test func phone() {
        #expect(PhoneParser.parse("tel:+63 917 1") == PhoneInfo(number: "+63 917 1"))
        #expect(PhoneParser.parse("TEL:%2B1%20555 ") == PhoneInfo(number: "+1 555"))
        #expect(PhoneParser.parse("tel:") == nil)
        #expect(PhoneParser.parse("sms:1") == nil)
    }

    @Test func sms() {
        #expect(SMSParser.parse("sms:+1?body=a%26b") == SMSInfo(number: "+1", body: "a&b"))
        #expect(SMSParser.parse("sms:+1&body=x") == SMSInfo(number: "+1", body: "x"))
        #expect(SMSParser.parse("SMS:+1") == SMSInfo(number: "+1"))
        #expect(SMSParser.parse("smsto:+1:hi: there %41") == SMSInfo(number: "+1", body: "hi: there %41"))
        #expect(SMSParser.parse("SMSTO:+1:") == SMSInfo(number: "+1"))
        #expect(SMSParser.parse("sms:?body=x") == nil)
        #expect(SMSParser.parse("smsto:") == nil)
    }

    @Test func mailto() {
        let e = EmailParser.parse("mailto:a@b.c,d@e.f?subject=S%20x&Body=B%26C&cc=z@z.z")
        #expect(e == EmailInfo(address: "a@b.c", subject: "S x", body: "B&C"))
        #expect(EmailParser.parse("mailto:?to=q@r.s")?.address == "q@r.s")
        #expect(EmailParser.parse("mailto:") == nil)
        #expect(EmailParser.parse("mailto:%61@b.c")?.address == "a@b.c")
    }

    @Test func matmsg() {
        let e = EmailParser.parse(#"matmsg:TO:a@b.c;SUB:x\;y;BODY:l1 \\ l2;;"#)
        #expect(e == EmailInfo(address: "a@b.c", subject: "x;y", body: #"l1 \ l2"#))
        #expect(EmailParser.parse("MATMSG:SUB:x;;") == nil)
    }

    @Test func geo() {
        #expect(GeoParser.parse("geo:14.5,120.9") == GeoInfo(latitude: "14.5", longitude: "120.9"))
        #expect(GeoParser.parse("geo:-1,2,3;u=35") == GeoInfo(latitude: "-1", longitude: "2", altitude: "3"))
        #expect(GeoParser.parse("geo:0,0?q=a%20b&z=3")?.query == "a b")
        #expect(GeoParser.parse("geo:abc,1") == nil)
        #expect(GeoParser.parse("geo:1") == nil)
        #expect(GeoParser.parse("geo:91,0") == nil)
        #expect(GeoParser.parse("geo:1,181") == nil)
        #expect(GeoParser.parse("geo:1e2,3") == nil)
        #expect(GeoParser.parse("geo:1,2,3,4") == nil)
        #expect(GeoParser.parse("geo:") == nil)
    }
}
