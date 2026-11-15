import Foundation
import Testing
import QRCore
@testable import QRUI

@Suite struct PeekClientTests {
    static let suffixes = """
        // ===BEGIN ICANN DOMAINS===
        com
        io
        // ===END ICANN DOMAINS===
        // ===BEGIN PRIVATE DOMAINS===
        github.io
        // ===END PRIVATE DOMAINS===
        """

    /// Lists with `suffixes` and these RDAP services, loaded by the core as a newer download (the
    /// shape rules want 50 services and one for com, so filler services come first).
    static func lists(rdap services: [[[String]]]) -> BundledData {
        var files = ListsTestRig.files()
        files.publicSuffixList = suffixes
        files.rdap = TestRDAPFile(services: (0..<60).map { [["tld\($0)"], ["https://rdap.example.org/"]] } + services)
        let bundle = TestLists(schema: 1, version: 9_999_999_999, built: "2026-12-01", files: files)
        return BundledData.load(downloaded: ListsTestRig.bytes(bundle))
    }

    static let psl = lists(rdap: [[["com"], ["https://rdap.example.org/"]]]).publicSuffixes
    static let page = Data("<html><head><title>Hello</title></head><body>hi</body></html>".utf8)
    static let stub: [AnyClass] = [PeekStubProtocol.self]

    static func link(_ text: String) throws -> LinkInfo {
        try #require(WebAddress.parse(text, psl: psl)).link
    }

    static func client(total: TimeInterval = 8, languages: [String] = ["en-US", "fr", "de", "es"]) -> PeekClient {
        PeekClient(protocolClasses: stub, totalTimeout: total, languages: languages)
    }

    static func html(_ headers: [String: String] = [:]) -> PeekStubProtocol.Reply {
        .ok(headers: headers.merging(["Content-Type": "text/html; charset=utf-8"]) { a, _ in a }, body: page)
    }

    // MARK: Redirects

    @Test func peekFollowsARedirectChainAndRecordsEveryHop() async throws {
        PeekStubProtocol.register("https://chain-a.example.com/", .redirect(status: 301, location: "https://chain-b.example.com/x"))
        PeekStubProtocol.register("https://chain-b.example.com/x", .redirect(status: 302, location: "/final"))
        PeekStubProtocol.register("https://chain-b.example.com/final",
                                  Self.html(["Content-Disposition": "inline"]))
        let capture = await Self.client().capture(try Self.link("https://chain-a.example.com/"))
        #expect(capture.stoppedBecause == .finished)
        #expect(capture.hops == [
            PeekHop(url: "https://chain-a.example.com/", status: 301),
            PeekHop(url: "https://chain-b.example.com/x", status: 302),
            PeekHop(url: "https://chain-b.example.com/final", status: 200),
        ])
        #expect(capture.body == Self.page)
        #expect(capture.contentType == "text/html; charset=utf-8")
        #expect(capture.contentDisposition == "inline")
    }

    @Test func peekFollowsTenRedirectsButNotEleven() async throws {
        // r0 -> r1 -> ... -> r10 answers 200: ten redirects, allowed.
        for i in 0..<10 {
            PeekStubProtocol.register("https://ten-\(i).example.com/", .redirect(location: "https://ten-\(i + 1).example.com/"))
        }
        PeekStubProtocol.register("https://ten-10.example.com/", Self.html())
        let ok = await Self.client().capture(try Self.link("https://ten-0.example.com/"))
        #expect(ok.stoppedBecause == .finished)
        #expect(ok.hops.count == 11)
        #expect(ok.hops.last == PeekHop(url: "https://ten-10.example.com/", status: 200))

        // r10 redirects once more: the 11th redirect is not followed.
        for i in 0...10 {
            PeekStubProtocol.register("https://eleven-\(i).example.com/", .redirect(location: "https://eleven-\(i + 1).example.com/"))
        }
        PeekStubProtocol.register("https://eleven-11.example.com/", Self.html())
        let capture = await Self.client().capture(try Self.link("https://eleven-0.example.com/"))
        #expect(capture.stoppedBecause == .tooManyRedirects)
        #expect(capture.hops.count == 11)
        #expect(capture.hops.last == PeekHop(url: "https://eleven-10.example.com/", status: 302))
        #expect(PeekStubProtocol.requests(to: "https://eleven-11.example.com/").isEmpty)
    }

    @Test func peekRecordsAnHTTPRedirectTargetWithoutFetchingIt() async throws {
        PeekStubProtocol.register("https://tohttp.example.com/", .redirect(location: "http://plain.example.com/login"))
        PeekStubProtocol.register("http://plain.example.com/login", Self.html())
        let capture = await Self.client().capture(try Self.link("https://tohttp.example.com/"))
        #expect(capture.stoppedBecause == .httpTarget)
        #expect(capture.hops == [
            PeekHop(url: "https://tohttp.example.com/", status: 302),
            PeekHop(url: "http://plain.example.com/login", status: nil),
        ])
        #expect(capture.body.isEmpty)
        #expect(PeekStubProtocol.requests(to: "http://plain.example.com/login").isEmpty)
    }

    @Test func peekRecordsACustomSchemeRedirectTargetWithoutFetchingIt() async throws {
        PeekStubProtocol.register("https://toapp.example.com/", .redirect(location: "myapp://open/thing"))
        let capture = await Self.client().capture(try Self.link("https://toapp.example.com/"))
        #expect(capture.stoppedBecause == .nonWebTarget)
        #expect(capture.hops.count == 2)
        #expect(capture.hops.first?.status == 302)
        #expect(capture.hops.last == PeekHop(url: "myapp://open/thing", status: nil))
    }

    @Test func peekDoesNotFetchAnHTTPOriginal() async throws {
        PeekStubProtocol.register("http://origin-http.example.com/", Self.html())
        let capture = await Self.client().capture(try Self.link("http://origin-http.example.com/"))
        #expect(capture.stoppedBecause == .httpTarget)
        #expect(capture.hops == [PeekHop(url: "http://origin-http.example.com/", status: nil)])
        #expect(PeekStubProtocol.requests(to: "http://origin-http.example.com/").isEmpty)
    }

    // MARK: Body, time, errors

    @Test func peekCutsABigBodyAtFiveHundredTwelveKilobytes() async throws {
        let big = Data(repeating: 0x61, count: 600 * 1024)
        PeekStubProtocol.register("https://big.example.com/", .ok(headers: ["Content-Type": "text/html"], body: big))
        let capture = await Self.client().capture(try Self.link("https://big.example.com/"))
        #expect(capture.stoppedBecause == .finished)
        #expect(capture.body.count == 512 * 1024)
        #expect(capture.hops == [PeekHop(url: "https://big.example.com/", status: 200)])
    }

    @Test func peekGivesUpWhenTheServerIsSlowPastTheTotalTimeout() async throws {
        PeekStubProtocol.register("https://slow.example.com/", .hang)
        let started = Date()
        let capture = await Self.client(total: 0.5).capture(try Self.link("https://slow.example.com/"))
        #expect(capture.stoppedBecause == .timeout)
        #expect(capture.hops == [PeekHop(url: "https://slow.example.com/", status: nil)])
        // The budget here is 0.5 s; under a CPU-heavy parallel test run the scheduler can delay the
        // timer by seconds, so assert only that it stops inside the real 8 s budget (SPEC §6).
        #expect(Date().timeIntervalSince(started) < 8)
    }

    @Test func peekMapsCertificateFailuresToTLSFailure() async throws {
        PeekStubProtocol.register("https://badcert.example.com/", .fail(.serverCertificateUntrusted))
        let capture = await Self.client().capture(try Self.link("https://badcert.example.com/"))
        #expect(capture.stoppedBecause == .tlsFailure)
        #expect(capture.body.isEmpty)
        let tls: [URLError.Code] = [.serverCertificateHasBadDate, .serverCertificateUntrusted,
                                    .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
                                    .secureConnectionFailed, .clientCertificateRejected, .clientCertificateRequired]
        for code in tls { #expect(PeekClient.reason(for: URLError(code)) == .tlsFailure) }
        for code in [URLError.Code.notConnectedToInternet, .cannotFindHost, .badServerResponse, .timedOut] {
            #expect(PeekClient.reason(for: URLError(code)) == .networkError)
        }
    }

    @Test func peekReportsOtherFailuresAsNetworkError() async throws {
        PeekStubProtocol.register("https://down.example.com/", .fail(.cannotConnectToHost))
        let capture = await Self.client().capture(try Self.link("https://down.example.com/"))
        #expect(capture.stoppedBecause == .networkError)
        #expect(capture.hops == [PeekHop(url: "https://down.example.com/", status: nil)])
    }

    // MARK: Headers and cookies

    @Test func peekSendsNoCookieOnTheSecondHopAfterASetCookie() async throws {
        PeekStubProtocol.register("https://cookie-a.example.com/",
                                  .redirect(location: "https://cookie-b.example.com/",
                                            headers: ["Set-Cookie": "session=abc; Path=/; Secure"]))
        PeekStubProtocol.register("https://cookie-b.example.com/", Self.html())
        let capture = await Self.client().capture(try Self.link("https://cookie-a.example.com/"))
        #expect(capture.stoppedBecause == .finished)
        let second = PeekStubProtocol.requests(to: "https://cookie-b.example.com/")
        #expect(second.count == 1)
        #expect(second.first?.keys.contains { $0.lowercased() == "cookie" } == false)
    }

    @Test func peekSendsExactlyTheAllowedHeadersOnEveryHop() async throws {
        PeekStubProtocol.register("https://hdr-a.example.com/", .redirect(location: "https://hdr-b.example.com/"))
        PeekStubProtocol.register("https://hdr-b.example.com/", Self.html())
        _ = await Self.client().capture(try Self.link("https://hdr-a.example.com/"))
        for url in ["https://hdr-a.example.com/", "https://hdr-b.example.com/"] {
            let headers = try #require(PeekStubProtocol.requests(to: url).first)
            #expect(headers["User-Agent"] == PeekClient.userAgent)
            #expect(headers["Accept"] == "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            #expect(headers["Accept-Language"] == "en-US,fr;q=0.9,de;q=0.8")
            // The system may add transport headers of its own; nothing identifying beyond ours.
            let extra = Set(headers.keys).subtracting(["User-Agent", "Accept", "Accept-Language", "Accept-Encoding", "Connection"])
            #expect(extra.isEmpty)
        }
        #expect(PeekStubProtocol.methods(of: "https://hdr-a.example.com/") == ["GET"])
    }

    @Test func peekBuildsAcceptLanguageFromTheFirstThreeTags() {
        #expect(PeekClient.acceptLanguage(for: ["en-US", "fr", "de", "es"]) == "en-US,fr;q=0.9,de;q=0.8")
        #expect(PeekClient.acceptLanguage(for: ["fil-PH"]) == "fil-PH")
        #expect(PeekClient.acceptLanguage(for: []) == "en")
    }

    // MARK: Domain age

    /// RDAP for `tld` at `base`; any other com lookup points at plain http, which the client refuses.
    static func bootstrap(_ base: String, tld: String = "com") -> BundledData {
        var services = [[[tld], [base]]]
        if tld != "com" { services.append([["com"], ["http://rdap-unused.example.invalid/"]]) }
        return lists(rdap: services)
    }
    static let now = ISO8601DateFormatter().date(from: "2026-11-10T00:00:00Z") ?? Date()
    static let rdapBody = Data(#"{"events":[{"eventAction":"registration","eventDate":"2026-10-31T00:00:00Z"}]}"#.utf8)

    @Test func peekAsksRDAPForTheRegistrableDomainAndReturnsTheAgeInDays() async throws {
        let url = "https://rdap-ok.example.net/domain/example.com"
        PeekStubProtocol.register(url, .ok(headers: ["Content-Type": "application/rdap+json"], body: Self.rdapBody))
        let age = await Self.client().registrationAge(for: try Self.link("https://www.sub.example.com/a?b=1"),
                                                     data: Self.bootstrap("https://rdap-ok.example.net/"), now: Self.now)
        #expect(age == 10)
        let headers = try #require(PeekStubProtocol.requests(to: url).first)
        #expect(headers["Accept"] == "application/rdap+json")
        #expect(headers["User-Agent"] == PeekClient.userAgent)
        #expect(headers["Cookie"] == nil)
        #expect(PeekStubProtocol.methods(of: url) == ["GET"])
    }

    @Test func peekAgeIsUnknownForNon200AndMissingEvents() async throws {
        let data = Self.bootstrap("https://rdap-bad.example.net/")
        PeekStubProtocol.register("https://rdap-bad.example.net/domain/gone.com", .ok(status: 404, body: Data("{}".utf8)))
        PeekStubProtocol.register("https://rdap-bad.example.net/domain/noevent.com", .ok(body: Data(#"{"events":[]}"#.utf8)))
        PeekStubProtocol.register("https://rdap-bad.example.net/domain/broken.com", .fail(.timedOut))
        for host in ["gone.com", "noevent.com", "broken.com"] {
            let age = await Self.client().registrationAge(for: try Self.link("https://\(host)/"), data: data, now: Self.now)
            #expect(age == nil)
        }
    }

    @Test func peekAgeIsUnknownWhenTheRDAPServiceIsPlainHTTPOrAbsent() async throws {
        let url = "http://rdap-plain.example.net/domain/example.com"
        PeekStubProtocol.register(url, .ok(body: Self.rdapBody))
        let plain = await Self.client().registrationAge(for: try Self.link("https://example.com/"),
                                                       data: Self.bootstrap("http://rdap-plain.example.net/"), now: Self.now)
        #expect(plain == nil)
        #expect(PeekStubProtocol.requests(to: url).isEmpty)
        let noService = await Self.client().registrationAge(for: try Self.link("https://example.com/"),
                                                           data: Self.bootstrap("https://x.example.net/", tld: "org"), now: Self.now)
        #expect(noService == nil)
    }

    @Test func peekUsesTheICANNLevelDomainForPrivateSuffixes() async throws {
        let link = try Self.link("https://someone.github.io/page")
        #expect(link.suffixIsPrivate)
        #expect(PeekClient.rdapDomain(for: link, data: Self.bootstrap("https://r.example.net/", tld: "io")) == "github.io")
        #expect(PeekClient.rdapDomain(for: try Self.link("https://a.b.example.com/"), data: Self.bootstrap("https://r.example.net/")) == "example.com")
        let url = "https://rdap-private.example.net/domain/github.io"
        PeekStubProtocol.register(url, .ok(body: Self.rdapBody))
        let age = await Self.client().registrationAge(for: link, data: Self.bootstrap("https://rdap-private.example.net/", tld: "io"), now: Self.now)
        #expect(age == 10)
        #expect(PeekStubProtocol.requests(to: url).count == 1)
    }

    @Test func peekDoesNotLookUpIPAddresses() async throws {
        let link = try Self.link("https://192.0.2.7/x")
        #expect(PeekClient.rdapDomain(for: link, data: Self.bootstrap("https://r.example.net/")) == nil)
    }
}
