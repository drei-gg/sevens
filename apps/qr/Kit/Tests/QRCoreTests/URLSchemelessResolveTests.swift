import Testing
@testable import QRCore

@Suite struct URLSchemelessResolveTests {
    static let psl = BundledData.shared.publicSuffixes

    // MARK: D9

    @Test(arguments: ["www.example.com", "example.com/x", "example.com?x=1", "Example.COM", "bücher.de/x", "a.b.example.co.uk#f"])
    func schemelessLinks(_ s: String) throws {
        let a = try #require(WebAddress.parseSchemeless(s, psl: Self.psl))
        #expect(a.assumedHTTPS)
        #expect(a.link.scheme == "https")
        #expect(a.link.original == s)
        #expect(a.link.registrableDomain != nil)
    }

    @Test func schemelessNormalized() throws {
        let a = try #require(WebAddress.parseSchemeless("www.example.com", psl: Self.psl))
        #expect(a.link.normalized == "https://www.example.com/")
        #expect(a.link.hostASCII == "www.example.com")
    }

    @Test(arguments: ["readme.txt", "hello world.com", "me@example.com", "localhost", "/path.com", ".example.com",
                      "example.com:8080/x", "mailto:x@example.com", "foo.zzzz", "1.2.3.4", "example..com",
                      "a_b.example.com", "https://example.com", "example.com.", "", " ", "example.com/a b"])
    func schemelessNotLinks(_ s: String) {
        #expect(WebAddress.parseSchemeless(s, psl: Self.psl) == nil)
    }

    // MARK: resolve

    static let base: LinkInfo = WebAddress.parse("https://example.com/a/b/c?x=1#f", psl: psl)!.link

    @Test(arguments: [
        ("/x", "https://example.com/x"),
        ("d", "https://example.com/a/b/d"),
        ("./d", "https://example.com/a/b/d"),
        ("../d", "https://example.com/a/d"),
        ("../../../d", "https://example.com/d"),
        ("d/e/../f", "https://example.com/a/b/d/f"),
        ("d/..", "https://example.com/a/b/"),
        ("?q=2", "https://example.com/a/b/c?q=2"),
        ("#z", "https://example.com/a/b/c?x=1#z"),
        ("", "https://example.com/a/b/c?x=1"),
        ("d?y#z", "https://example.com/a/b/d?y#z"),
        ("//evil.com/p", "https://evil.com/p"),
        (#"\\evil.com\p"#, "https://evil.com/p"),
        (#"/\evil.com"#, "https://evil.com/"),
        ("http://other.org/q", "http://other.org/q"),
        ("HTTPS://Other.ORG", "https://other.org/"),
        ("  /x\n", "https://example.com/x"),
        (#"/a\b"#, "https://example.com/a/b"),
        ("/a/%2e%2e/z", "https://example.com/z"),
    ])
    func resolved(_ pair: (String, String)) throws {
        let r = try #require(WebAddress.resolve(pair.0, against: Self.base, psl: Self.psl))
        #expect(r.link.normalized == pair.1)
    }

    @Test(arguments: ["javascript:alert(1)", "ftp://example.com/x", "mailto:a@b.co", "data:text/html,hi", "http:///"])
    func resolveRejectsNonHttp(_ s: String) {
        #expect(WebAddress.resolve(s, against: Self.base, psl: Self.psl) == nil)
    }

    @Test func resolveKeepsUserinfoAndPort() throws {
        let b = try #require(WebAddress.parse("http://u@example.com:8080/x/y", psl: Self.psl)).link
        let r = try #require(WebAddress.resolve("z", against: b, psl: Self.psl))
        #expect(r.link.normalized == "http://u@example.com:8080/x/z")
        #expect(r.link.userinfo == "u")
        #expect(r.link.port == 8080)
    }

    @Test func resolveAgainstIPBase() throws {
        let b = try #require(WebAddress.parse("http://[::1]:3000/a/b", psl: Self.psl)).link
        let r = try #require(WebAddress.resolve("c", against: b, psl: Self.psl))
        #expect(r.link.normalized == "http://[::1]:3000/a/c")
        #expect(r.hostKind == .ipv6)
    }

    @Test func resolveGivesFreshPslFields() throws {
        let r = try #require(WebAddress.resolve("//user.github.io/x", against: Self.base, psl: Self.psl))
        #expect(r.link.suffixIsPrivate)
        #expect(r.link.registrableDomain == "user.github.io")
    }
}
