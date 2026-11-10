import Foundation
import Testing
@testable import QRCore

@Suite struct AnalyzerFixtureTests {
    let analyzer = Analyzer(data: BundledData.shared)

    @Test(arguments: ["links.json", "classify.json", "false-positives.json"])
    func analyzerFixtureFilePasses(file: String) throws {
        let cases = try Fixtures.load(file)
        #expect(!cases.isEmpty)
        for c in cases {
            let problems = Fixtures.mismatches(c, analyzer.analyze(c.scannedCode))
            #expect(problems.isEmpty, "\(c.id): \(problems.joined(separator: "; "))")
        }
    }

    @Test func analyzerFalsePositiveSetHasNoStrongSignal() throws {
        let cases = try Fixtures.load("false-positives.json")
        #expect(cases.count >= 100)
        var careful = 0
        for c in cases {
            let a = analyzer.analyze(c.scannedCode)
            #expect(a.payload.typeID == "link", "\(c.id)")
            #expect(a.level <= .careful, "\(c.id) \(c.input ?? "")")
            #expect(!a.allSignals.contains { $0.severity == .strong }, "\(c.id) \(c.input ?? "")")
            if a.level == .careful { careful += 1 }
        }
        #expect(careful <= 6, "only honest warnings (shortener, plain http, IP) may be careful")
    }

    @Test func analyzerEveryOfflineSignalHasFiringAndNonFiringFixtures() throws {
        let cases = try Fixtures.load("links.json")
        let offline: [SignalID] = [.dangerousScheme, .userinfoTrick, .mixedScript, .lookalikeBrand, .brandInDomain, .nearBrand, .ipHost,
                                   .shortener, .plainHTTP, .riskyTLD, .deepSubdomains, .obfuscatedPath, .unknownScheme, .assumedHTTPS]
        for id in offline {
            let fires = cases.filter { $0.expect.signals.contains(id.rawValue) || ($0.expect.info ?? []).contains(id.rawValue) }.count
            #expect(fires >= 3, "\(id) has \(fires) firing cases in links.json")
        }
        #expect(cases.filter { $0.id.hasPrefix("link-near-") }.count >= 30)
    }

    @Test func analyzerFixtureCountsReport() throws {
        var total = 0
        for f in ["links.json", "classify.json", "false-positives.json"] { total += try Fixtures.load(f).count }
        #expect(total >= 300)
    }
}

@Suite struct AnalyzerBehaviourTests {
    let analyzer = Analyzer(data: BundledData.shared)
    func run(_ s: String, _ sym: Symbology = .qr) -> Analysis { analyzer.analyze(ScannedCode(text: s, symbology: sym)) }

    @Test func analyzerLevelRule() {
        #expect(analyzer.level(for: []) == .normal)
        #expect(analyzer.level(for: [Signal(.assumedHTTPS)]) == .normal)
        #expect(analyzer.level(for: [Signal(.plainHTTP)]) == .careful)
        #expect(analyzer.level(for: [Signal(.plainHTTP), Signal(.ipHost)]) == .careful)
        #expect(analyzer.level(for: [Signal(.plainHTTP), Signal(.ipHost), Signal(.shortener)]) == .likelyScam)
        #expect(analyzer.level(for: [Signal(.userinfoTrick)]) == .likelyScam)
    }

    @Test func analyzerThreeWarningsMakeLikelyScam() {
        let a = run("http://203.0.113.9/")
        #expect(a.level == .careful)
        let b = run("http://bit.ly.deep.a.b.c.example.top/")
        #expect(b.signals.map(\.id).contains(.deepSubdomains) && b.signals.map(\.id).contains(.riskyTLD) && b.signals.map(\.id).contains(.plainHTTP))
        #expect(b.level == .likelyScam)
    }

    @Test func analyzerEmbeddedLinksFillInfoLinksAndLevel() {
        let a = run("pay https://paypa1.com/x or https://example.com/ok")
        guard case .text(let info) = a.payload else { Issue.record("not text"); return }
        #expect(info.links == ["https://paypa1.com/x", "https://example.com/ok"])
        #expect(a.embedded.count == 2)
        #expect(a.signals.isEmpty)
        #expect(a.level == .likelyScam)
        #expect(a.embedded[0].level == .likelyScam && a.embedded[1].level == .normal)
        #expect(a.embedded[0].code.symbology == .qr)
    }

    @Test func analyzerEmbeddedKeepsSymbologyAndNeverRecurses() {
        let a = run("see www.example.com", .pdf417)
        #expect(a.embedded.first?.code.symbology == .pdf417)
        #expect(a.embedded.allSatisfy { $0.embedded.isEmpty })
        let b = run("https://a.example/ https://b.example/ " + String(repeating: "http://c.example/ ", count: 5))
        #expect(b.embedded.count <= 10)
    }

    @Test func analyzerAtMostTenEmbedded() {
        let text = "links: " + (0..<25).map { "http://h\($0).example.com/" }.joined(separator: " ")
        let a = run(text)
        #expect(a.embedded.count == 10)
        guard case .text(let t) = a.payload else { Issue.record("not text"); return }
        #expect(t.links.count == 10)
    }

    @Test func analyzerContactAndEventLinksListed() {
        let c = run("BEGIN:VCARD\nVERSION:3.0\nFN:A\nURL:https://example.com\nNOTE:also www.example.org\nEND:VCARD")
        guard case .contact(let ci) = c.payload else { Issue.record("not contact"); return }
        #expect(ci.links == ["https://example.com", "www.example.org"])
        #expect(c.embedded.count == 2)
        let e = run("BEGIN:VEVENT\nSUMMARY:x\nDTSTART:20261110T090000Z\nURL:https://example.com\nDESCRIPTION:http://e.example/1\nLOCATION:https://e.example/2\nEND:VEVENT")
        guard case .event(let ei) = e.payload else { Issue.record("not event"); return }
        #expect(ei.links == ["https://example.com", "http://e.example/1", "https://e.example/2"])
    }

    @Test func analyzerEightKilobyteCap() {
        let big = String(repeating: "\u{00E9}", count: 6000) + " https://paypa1.com/x"   // 12 KB of UTF-8
        let a = run(big)
        guard case .text(let t) = a.payload else { Issue.record("not text"); return }
        #expect(t.isTruncated)
        #expect(t.text.utf8.count <= 8192)
        #expect(a.embedded.isEmpty)
        let exactly = String(repeating: "a", count: 8192)
        guard case .text(let t2) = run(exactly).payload else { Issue.record("not text"); return }
        #expect(!t2.isTruncated)
    }

    @Test func analyzerBinaryAndEmpty() {
        let a = analyzer.analyze(ScannedCode(text: nil, bytes: [1, 2, 3], symbology: .dataMatrix))
        guard case .binary(let n) = a.payload else { Issue.record("not binary"); return }
        #expect(n == 3 && a.level == .normal && a.signals.isEmpty)
        guard case .binary(let z) = run("").payload else { Issue.record("not binary"); return }
        #expect(z == 0)
    }

    @Test func analyzerNeverDuplicatesSignalIDs() throws {
        for c in try Fixtures.all() {
            let a = analyzer.analyze(c.scannedCode)
            for x in [a] + a.embedded {
                #expect(Set(x.signals.map(\.id)).count == x.signals.count, "\(c.id)")
                #expect(x.signals == x.signals.inStableOrder(), "\(c.id)")
            }
        }
    }

    @Test func analyzerHostileInputsDoNotCrash() {
        let inputs = ["http://", "https://@", "https://[", "https://[::1", "http://:80", "http://%", "javascript:", "data:", ":", "::::", "a:b",
                      "WIFI:;;", "BEGIN:VCARD", "BEGIN:VEVENT", "000201", "BCD", "SPC", "upi://", "mailto:", "tel:", "sms:", "geo:", "bitcoin:",
                      String(repeating: "(", count: 5000), String(repeating: "https://", count: 1500), "\u{0}\u{1}\u{2}", "\u{FEFF}https://x.com"]
        for s in inputs { _ = run(s) }
    }
}

@Suite struct AnalyzerPerformanceTests {
    @Test func analyzerThousandMixedCodesUnderTwoSeconds() throws {
        let analyzer = Analyzer(data: BundledData.shared)
        var codes: [ScannedCode] = []
        let cases = try Fixtures.all()
        var i = 0
        while codes.count < 1000 { codes.append(cases[i % cases.count].scannedCode); i += 1 }
        let start = Date()
        var levels = 0
        for c in codes { levels += analyzer.analyze(c).level.rawValue }
        let elapsed = Date().timeIntervalSince(start)
        print("analyzer: 1000 mixed codes in \(Int(elapsed * 1000)) ms (\(levels))")
        #expect(elapsed < 2.0)
    }
}
