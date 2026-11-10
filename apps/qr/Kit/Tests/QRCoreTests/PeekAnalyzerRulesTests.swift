import Foundation
import Testing
@testable import QRCore

@Suite struct PeekAnalyzerRulesTests {
    let analyzer = Analyzer(data: BundledData.shared)
    let peek = PeekAnalyzer(data: BundledData.shared)

    func run(_ url: String = "https://page.example.com/", body: String, contentType: String? = "text/html", disposition: String? = nil,
             stop: StopReason = .finished, age: Int? = 5000) -> PeekReport {
        let capture = PeekCapture(hops: [PeekHop(url: url, status: 200)], stoppedBecause: stop,
                                  contentType: contentType, contentDisposition: disposition, body: Array(body.utf8))
        return peek.report(original: analyzer.analyze(ScannedCode(text: url)), capture: capture, ageDays: age)
    }

    func ids(_ r: PeekReport) -> [SignalID] { r.signals.map(\.id) }
    func form(_ input: String) -> String { "<html><body><p>\(String(repeating: "text ", count: 60))</p><form>\(input)</form></body></html>" }

    @Test func peekAnalyzerSecretKinds() {
        let cases: [(String, String)] = [
            ("<input type=password name=p>", "password"),
            ("<input type=text autocomplete=current-password>", "password"),
            ("<input type=text autocomplete=one-time-code>", "code"),
            ("<input name=otp>", "code"),
            ("<input name=mpin>", "code"),
            ("<input placeholder=\"Enter your PIN\">", "code"),
            ("<input id=passcode>", "code"),
            ("<input placeholder=\"Verification code\">", "code"),
            ("<input autocomplete=cc-number>", "card"),
            ("<input autocomplete=cc-csc>", "card"),
            ("<input name=\"card number\">", "card"),
            ("<input name=cvv>", "card"),
            ("<input name=cvc>", "card"),
        ]
        for (input, kind) in cases {
            let r = run(body: form(input))
            #expect(r.signals.first(where: { $0.id == .asksForSecret })?.args["kind"] == kind, "\(input)")
        }
    }

    @Test func peekAnalyzerPinNeedsWordBoundaries() {
        for input in ["<input name=pinterest>", "<input name=shipping>", "<input name=email>", "<input type=hidden name=pin>"] {
            #expect(!ids(run(body: form(input))).contains(.asksForSecret), "\(input)")
        }
    }

    @Test func peekAnalyzerSniffsOnlyHTMLOrAngleBracket() {
        let html = "<html><head><title>Hello</title></head><body>\(String(repeating: "x ", count: 150))</body></html>"
        #expect(run(body: html, contentType: "text/html").title == "Hello")
        #expect(run(body: html, contentType: "application/xhtml+xml").title == "Hello")
        #expect(run(body: "  \n" + html, contentType: "application/octet-stream").title == "Hello", "first non-space byte is <")
        #expect(run(body: html, contentType: nil).title == "Hello")
        #expect(run(body: "{\"title\":\"<title>No</title>\"}", contentType: "application/json").title == nil)
        #expect(run(body: "plain text <title>No</title>", contentType: "text/plain").title == nil)
    }

    @Test func peekAnalyzerStopReasons() {
        func one(_ stop: StopReason, hops: [PeekHop]) -> [SignalID] {
            let capture = PeekCapture(hops: hops, stoppedBecause: stop)
            return peek.report(original: analyzer.analyze(ScannedCode(text: hops[0].url)), capture: capture, ageDays: 5000).signals.map(\.id)
        }
        let a = PeekHop(url: "https://a.example.com/", status: 301)
        #expect(one(.tooManyRedirects, hops: [a]).contains(.tooManyRedirects))
        #expect(one(.httpTarget, hops: [a, PeekHop(url: "http://b.example.com/", status: nil)]).contains(.redirectsToHTTP))
        #expect(one(.nonWebTarget, hops: [a, PeekHop(url: "tel:+631", status: nil)]).contains(.redirectLeavesWeb))
        #expect(one(.tlsFailure, hops: [a, PeekHop(url: "https://b.example.com/", status: nil)]).contains(.tlsProblem))
        #expect(one(.timeout, hops: [a]).contains(.peekIncomplete))
        #expect(one(.networkError, hops: [a]).contains(.peekIncomplete))
        #expect(!one(.finished, hops: [a]).contains(.peekIncomplete))
    }

    @Test func peekAnalyzerFinalLinkIsLastFetchedHop() {
        let hops = [PeekHop(url: "https://a.example.com/", status: 302), PeekHop(url: "https://b.example.org/x", status: 200),
                    PeekHop(url: "http://c.example.net/", status: nil)]
        let capture = PeekCapture(hops: hops, stoppedBecause: .httpTarget)
        let r = peek.report(original: analyzer.analyze(ScannedCode(text: hops[0].url)), capture: capture, ageDays: 400)
        #expect(r.finalLink?.hostASCII == "b.example.org")
        #expect(r.hopAnalyses.count == 3)
        #expect(r.hopAnalyses[2].signals.map(\.id).contains(.plainHTTP))
        let none = peek.report(original: analyzer.analyze(ScannedCode(text: hops[0].url)),
                               capture: PeekCapture(hops: [PeekHop(url: hops[0].url, status: nil)], stoppedBecause: .timeout), ageDays: nil)
        #expect(none.finalLink == nil)
        #expect(!none.signals.map(\.id).contains(.ageUnknown))
    }

    @Test func peekAnalyzerLevelCombinesOriginalAndHops() {
        // Original is a careful link (shortener); a clean page does not lower it.
        let r = run("https://bit.ly/abc", body: "<html><body>\(String(repeating: "word ", count: 80))</body></html>")
        #expect(r.level == .careful)
        // A strong hop raises it even when the page itself is clean.
        let hops = [PeekHop(url: "https://example.com/a", status: 302), PeekHop(url: "https://paypa1.com/", status: 200)]
        let r2 = peek.report(original: analyzer.analyze(ScannedCode(text: hops[0].url)),
                             capture: PeekCapture(hops: hops, stoppedBecause: .finished, contentType: "text/html"), ageDays: 5000)
        #expect(r2.level == .likelyScam)
        #expect(r2.signals.isEmpty || r2.signals.allSatisfy { $0.severity == .info })
    }

    @Test func peekAnalyzerAgeBoundaries() {
        for (age, expected) in [(0, SignalID.domainVeryNew), (29, .domainVeryNew), (30, .domainNew), (182, .domainNew)] as [(Int, SignalID)] {
            let r = run(body: "", contentType: nil, age: age)
            #expect(ids(r).contains(expected), "age \(age)")
            #expect(r.signals.first(where: { $0.id == expected })?.args["days"] == String(age))
        }
        #expect(!ids(run(body: "", contentType: nil, age: 183)).contains(.domainNew))
        #expect(ids(run(body: "", contentType: nil, age: nil)).contains(.ageUnknown))
    }

    @Test func peekAnalyzerDownloads() {
        #expect(ids(run("https://f.example.com/x", body: "", contentType: "application/zip", disposition: "Attachment; filename=x")).contains(.downloadsFile))
        for ext in ["apk", "ipa", "mobileconfig", "exe", "msi", "dmg", "pkg", "zip", "scr", "bat"] {
            #expect(ids(run("https://f.example.com/a.\(ext)?v=1#x", body: "", contentType: nil)).contains(.downloadsFile), "\(ext)")
        }
        #expect(!ids(run("https://f.example.com/a.pdf", body: "", contentType: "application/pdf")).contains(.downloadsFile))
        #expect(!ids(run("https://f.example.com/zip", body: "", contentType: nil)).contains(.downloadsFile))
    }

    @Test func peekAnalyzerSharedHostingUsesFinalSuffix() {
        let r = run("https://user.github.io/", body: "")
        #expect(ids(r).contains(.sharedHostingDomain))
        #expect(!ids(run("https://example.com/", body: "")).contains(.sharedHostingDomain))
    }

    @Test func peekAnalyzerSignalOrderAndUniqueness() {
        let body = "<html><head><meta http-equiv=refresh content=\"0;url=https://x.example.net/\"></head><body><form action=\"https://z.example.net/\"><input type=password></form><script>top.location='x'</script></body></html>"
        let r = run(body: body, age: 3)
        #expect(r.signals == r.signals.inStableOrder())
        #expect(Set(ids(r)).count == ids(r).count)
        #expect(ids(r).contains(.domainVeryNew) && ids(r).contains(.formPostsElsewhere) && ids(r).contains(.scriptRedirect))
    }

    @Test func peekAnalyzerHostileBodiesDoNotCrash() {
        var junk: [UInt8] = []
        for i in 0..<20_000 { junk.append(UInt8(truncatingIfNeeded: i &* 31 &+ 7)) }
        let capture = PeekCapture(hops: [PeekHop(url: "https://x.example.com/", status: 200)], stoppedBecause: .finished,
                                  contentType: "text/html", body: junk)
        _ = peek.report(original: analyzer.analyze(ScannedCode(text: "https://x.example.com/")), capture: capture, ageDays: 1)
        let empty = PeekCapture(hops: [], stoppedBecause: .networkError)
        let r = peek.report(original: analyzer.analyze(ScannedCode(text: "https://x.example.com/")), capture: empty, ageDays: nil)
        #expect(r.finalLink == nil && r.hopAnalyses.isEmpty)
    }
}
