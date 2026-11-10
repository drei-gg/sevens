import Testing
@testable import QRCore

@Suite struct URLWebAddressTests {
    static let psl = BundledData.shared.publicSuffixes
    func parse(_ s: String) -> WebAddress? { WebAddress.parse(s, psl: Self.psl) }

    // MARK: userinfo, backslashes, tricks

    @Test func userinfoTrick() throws {
        let a = try #require(parse("https://bank.com@evil.site/login"))
        #expect(a.link.userinfo == "bank.com")
        #expect(a.link.hostASCII == "evil.site")
        #expect(a.link.registrableDomain == "evil.site")
        #expect(a.link.pathAndQuery == "/login")
        #expect(a.link.normalized == "https://bank.com@evil.site/login")
    }

    @Test func multipleAtSignsUseTheLast() throws {
        let a = try #require(parse("https://a@b@evil.site/"))
        #expect(a.link.userinfo == "a@b")
        #expect(a.link.hostASCII == "evil.site")
    }

    @Test func userAndPasswordWithPort() throws {
        let a = try #require(parse("http://user:pw@example.com:8080/x"))
        #expect(a.link.userinfo == "user:pw")
        #expect(a.link.port == 8080)
    }

    @Test func emptyUserinfoIsNil() throws {
        #expect(try #require(parse("https://@example.com/")).link.userinfo == nil)
    }

    @Test func backslashesAfterScheme() throws {
        let a = try #require(parse(#"https:\\evil.com\path"#))
        #expect(a.link.hostASCII == "evil.com")
        #expect(a.link.pathAndQuery == "/path")
    }

    @Test func backslashEndsAuthority() throws {
        let a = try #require(parse(#"https://good.com\@evil.com"#))
        #expect(a.link.hostASCII == "good.com")
        #expect(a.link.userinfo == nil)
        #expect(a.link.pathAndQuery == "/@evil.com")
    }

    @Test func backslashesInPathOnlyBeforeQuery() throws {
        let a = try #require(parse(#"https://example.com/a\b?x=\y#f\g"#))
        #expect(a.link.pathAndQuery == #"/a/b?x=\y#f\g"#)
    }

    @Test func noSlashesAtAll() throws {
        #expect(try #require(parse("https:evil.com/x")).link.hostASCII == "evil.com")
        #expect(try #require(parse("http:////evil.com")).link.hostASCII == "evil.com")
    }

    @Test func percentEncodedHost() throws {
        #expect(try #require(parse("https://exa%6Dple.com/")).link.hostASCII == "example.com")
        #expect(parse("https://exa%20mple.com/") == nil)
        #expect(parse("https://exa%zzmple.com/") == nil)
    }

    @Test func fragmentInsteadOfPath() throws {
        let a = try #require(parse("https://example.com#frag"))
        #expect(a.link.pathAndQuery == "/#frag")
        #expect(try #require(parse("https://example.com?q=1#f")).link.pathAndQuery == "/?q=1#f")
    }

    // MARK: whitespace, case, normalisation

    @Test func tabsAndNewlinesAreRemoved() throws {
        let a = try #require(parse("ht\ttps://exa\nmple.com/a\r\nb"))
        #expect(a.link.scheme == "https")
        #expect(a.link.hostASCII == "example.com")
        #expect(a.link.pathAndQuery == "/ab")
    }

    @Test func controlsAndSpacesAreTrimmed() throws {
        let raw = "  \u{1}https://example.com/x \u{7} "
        let a = try #require(parse(raw))
        #expect(a.link.normalized == "https://example.com/x")
        #expect(a.link.original == raw)
    }

    @Test func uppercaseSchemeAndHost() throws {
        let a = try #require(parse("HTTPS://WWW.EXAMPLE.COM/Path"))
        #expect(a.link.scheme == "https")
        #expect(a.link.hostASCII == "www.example.com")
        #expect(a.link.pathAndQuery == "/Path")
        #expect(a.link.registrableDomain == "example.com")
        #expect(a.link.publicSuffix == "com")
    }

    @Test func trailingDotDropped() throws {
        #expect(try #require(parse("https://example.com./")).link.hostASCII == "example.com")
    }

    @Test(arguments: ["https://example\u{3002}com/", "https://example\u{FF0E}com/", "https://example\u{FF61}com/"])
    func ideographicFullStops(_ s: String) throws {
        #expect(try #require(parse(s)).link.hostASCII == "example.com")
    }

    @Test func emptyOrMissingHost() {
        #expect(parse("http:///") == nil)
        #expect(parse("https://") == nil)
        #expect(parse("https://@/") == nil)
        #expect(parse("https://:80/") == nil)
    }

    @Test func notHttpIsNil() {
        #expect(parse("ftp://example.com/") == nil)
        #expect(parse("javascript:alert(1)") == nil)
        #expect(parse("example.com") == nil)
        #expect(parse("") == nil)
    }

    @Test func oversizedInputIsBounded() throws {
        let a = try #require(parse("https://example.com/" + String(repeating: "a", count: 20000)))
        #expect(a.link.pathAndQuery.utf8.count <= 8192)
        let multi = "https://example.com/" + String(repeating: "é", count: 10000)
        let b = try #require(parse(multi))
        #expect(b.link.pathAndQuery.utf8.count <= 8192)
        #expect(WebAddress.parse(String(repeating: "x", count: 100_000), psl: Self.psl) == nil)
    }

    // MARK: scheme(of:)

    @Test(arguments: [("https://x.com", "https"), ("HTTP://x", "http"), ("  javascript:alert(1)", "javascript"),
                      ("mailto:a@b.c", "mailto"), ("web+app:x", "web+app"), ("example.com:8080/x", "example.com")])
    func schemeDetected(_ pair: (String, String)) {
        #expect(WebAddress.scheme(of: pair.0) == pair.1)
    }

    @Test(arguments: ["hello", "1abc:x", ":x", "a b:c", "path/to:x", "x?y=a:b", "", "é:x"])
    func schemeAbsent(_ s: String) {
        #expect(WebAddress.scheme(of: s) == nil)
    }

    // MARK: ports

    @Test func defaultPortsAreDropped() throws {
        #expect(try #require(parse("http://example.com:80/")).link.port == nil)
        #expect(try #require(parse("https://example.com:443")).link.port == nil)
        #expect(try #require(parse("https://example.com:")).link.port == nil)
    }

    @Test func customPortsKept() throws {
        #expect(try #require(parse("https://example.com:8443/x")).link.port == 8443)
        let a = try #require(parse("http://example.com:443/"))
        #expect(a.link.port == 443)
        #expect(a.link.normalized == "http://example.com:443/")
    }

    @Test func badPorts() {
        #expect(parse("https://example.com:99999/") == nil)
        #expect(parse("https://example.com:80a/") == nil)
        #expect(parse("https://example.com:1:2/") == nil)
    }

    // MARK: IP hosts

    @Test(arguments: [("http://3232235777/", "192.168.1.1"), ("http://0x7f.1/", "127.0.0.1"),
                      ("http://0177.0.0.1/", "127.0.0.1"), ("http://127.1/", "127.0.0.1"),
                      ("http://192.168.1.1./", "192.168.1.1"), ("http://0xC0A80101/", "192.168.1.1"),
                      ("http://10.0.0.1:81/x", "10.0.0.1")])
    func ipv4Forms(_ pair: (String, String)) throws {
        let a = try #require(parse(pair.0))
        #expect(a.hostKind == .ipv4)
        #expect(a.link.hostASCII == pair.1)
        #expect(a.link.isIPAddress)
        #expect(a.link.registrableDomain == nil)
        #expect(a.link.publicSuffix == nil)
    }

    @Test(arguments: ["http://256.1.1.1/", "http://1.2.3.4.5/", "http://4294967296/", "http://1.2.3.08/", "http://999999999999999/"])
    func ipv4OutOfRange(_ s: String) {
        #expect(parse(s) == nil)
    }

    @Test func ipv6() throws {
        let a = try #require(parse("http://[::1]:8080/"))
        #expect(a.hostKind == .ipv6)
        #expect(a.link.hostASCII == "[::1]")
        #expect(a.link.port == 8080)
        #expect(a.link.isIPAddress)
        #expect(try #require(parse("https://[2001:DB8::1]/")).link.hostASCII == "[2001:db8::1]")
        #expect(parse("http://[::1/") == nil)
        #expect(parse("http://[zz]/") == nil)
    }

    @Test func lastLabelNotNumericIsDomain() throws {
        let a = try #require(parse("http://1.2.3.4x/"))
        #expect(a.hostKind == .domain)
    }

    // MARK: IDN

    @Test func unicodeHostGetsPunycode() throws {
        let a = try #require(parse("https://bücher.de/x"))
        #expect(a.link.hostASCII == "xn--bcher-kva.de")
        #expect(a.link.hostUnicode == "bücher.de")
        #expect(a.link.registrableDomain == "bücher.de")
        #expect(a.link.publicSuffix == "de")
        #expect(a.link.normalized == "https://xn--bcher-kva.de/x")
        #expect(!a.hasMalformedLabel)
    }

    @Test func punycodeHostDecodes() throws {
        let a = try #require(parse("https://xn--bcher-kva.de"))
        #expect(a.link.hostUnicode == "bücher.de")
        #expect(a.link.hostASCII == "xn--bcher-kva.de")
    }

    @Test func uppercaseAndDecomposedUnicode() throws {
        #expect(try #require(parse("https://BÜCHER.de")).link.hostASCII == "xn--bcher-kva.de")
        #expect(try #require(parse("https://bu\u{308}cher.de")).link.hostASCII == "xn--bcher-kva.de")
    }

    @Test(arguments: ["https://xn--ab!c.com/", "https://xn--a-.com/", "https://xn--.com/"])
    func malformedPunycodeLabel(_ s: String) throws {
        let a = try #require(parse(s))
        #expect(a.hasMalformedLabel)
        #expect(a.link.hostASCII.hasPrefix("xn--"))
    }

    @Test func idnSuffixIsReportedInUnicode() throws {
        let a = try #require(parse("https://食狮.中国/"))
        #expect(a.link.hostASCII == "xn--85x722f.xn--fiqs8s")
        #expect(a.link.publicSuffix == "中国")
        #expect(a.link.registrableDomain == "食狮.中国")
    }

    // MARK: suffixes

    @Test func privateSuffixHost() throws {
        let a = try #require(parse("https://user.github.io/"))
        #expect(a.link.registrableDomain == "user.github.io")
        #expect(a.link.publicSuffix == "github.io")
        #expect(a.link.suffixIsPrivate)
        #expect(a.suffixIsKnown)
    }

    @Test func hostThatIsASuffix() throws {
        let a = try #require(parse("https://co.uk/"))
        #expect(a.link.registrableDomain == nil)
        #expect(a.link.publicSuffix == "co.uk")
    }

    @Test func wildcardAndExceptionThroughParse() throws {
        #expect(try #require(parse("https://a.b.test.ck/")).link.registrableDomain == "b.test.ck")
        #expect(try #require(parse("https://www.ck/")).link.registrableDomain == "www.ck")
        #expect(try #require(parse("https://test.ck/")).link.registrableDomain == nil)
    }

    @Test func unknownSuffixFallsBackToTheLastLabel() throws {
        // Contract (LinkInfo.registrableDomain): the last label is the suffix when the ending isn't listed.
        let a = try #require(parse("https://www.example.zzzz/"))
        #expect(a.link.registrableDomain == "example.zzzz")
        #expect(a.link.publicSuffix == "zzzz")
        #expect(try #require(parse("https://zzzz/")).link.registrableDomain == nil)
        #expect(try #require(parse("https://203.0.113.9/")).link.registrableDomain == nil)
        #expect(!a.suffixIsKnown)
        #expect(!a.assumedHTTPS)
    }
}
