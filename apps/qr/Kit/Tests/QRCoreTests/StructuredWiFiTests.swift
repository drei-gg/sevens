import Testing
@testable import QRCore

@Suite struct StructuredWiFiTests {
    @Test func basicAndAnyOrder() {
        let a = WiFiParser.parse("WIFI:T:WPA;S:Net;P:pw;;")
        #expect(a == WiFiInfo(ssid: "Net", security: "WPA", password: "pw"))
        #expect(WiFiParser.parse("WIFI:P:pw;H:true;S:Net;T:WEP;;") ==
                WiFiInfo(ssid: "Net", security: "WEP", password: "pw", isHidden: true))
        #expect(WiFiParser.parse("  wifi:S:x;;")?.ssid == "x")
    }

    @Test func escapesAndQuotes() {
        #expect(WiFiParser.parse(#"WIFI:S:a\;b\,c\:d\\e\"f;P:\;;;"#) ==
                WiFiInfo(ssid: #"a;b,c:d\e"f"#, password: ";"))
        #expect(WiFiParser.parse(#"WIFI:S:"abcd";P:"0011";;"#) == WiFiInfo(ssid: "abcd", password: "0011"))
        #expect(WiFiParser.parse("WIFI:S:has:colon;;")?.ssid == "has:colon")
    }

    @Test func securityAndOpen() {
        #expect(WiFiParser.parse("WIFI:T:nopass;S:x;;")?.security == "NOPASS")
        #expect(WiFiParser.parse("WIFI:T:nopass;S:x;;")?.isOpen == true)
        #expect(WiFiParser.parse("WIFI:T:;S:x;;")?.security == nil)
        #expect(WiFiParser.parse("WIFI:S:x;;")?.isOpen == true)
        #expect(WiFiParser.parse("WIFI:T:WPA;S:x;P:y;;")?.isOpen == false)
    }

    @Test func enterprise() {
        let w = WiFiParser.parse("WIFI:T:WPA2-EAP;S:Corp;E:TTLS;I:ann;;")
        #expect(w?.eapMethod == "TTLS")
        #expect(w?.identity == "ann")
        #expect(w?.isEnterprise == true)
    }

    @Test func rejects() {
        #expect(WiFiParser.parse("WIFI:T:WPA;P:x;;") == nil)
        #expect(WiFiParser.parse("WIFI:S:;;") == nil)
        #expect(WiFiParser.parse("WIFI:") == nil)
        #expect(WiFiParser.parse("hello WIFI:S:x;;") == nil)
        #expect(WiFiParser.parse("") == nil)
    }
}
