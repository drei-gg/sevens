import Foundation
import Testing
import QRCore
@testable import QRUI

/// SPEC §7 and §4.1: a Peek that raises the verdict keeps it for the result (shown, with its
/// reasons, and counted by Open's confirmation on every route), and a text whose verdict comes
/// from a link inside it says which link.
@Suite struct ResultPeekVerdictTests {
    static func report(_ level: Level, signals: [Signal] = [], hops: [Analysis] = []) -> PeekReport {
        PeekReport(finalLink: nil, hopAnalyses: hops, title: nil, signals: signals, level: level)
    }

    @Test func peekRaisesTheEffectiveLevel() {
        let raised = Self.report(.likelyScam, signals: [Signal(.domainVeryNew, ["days": "3"])])
        #expect(ResultActionPlan.effectiveLevel(.normal, peek: raised) == .likelyScam)
        #expect(ResultActionPlan.effectiveLevel(.careful, peek: Self.report(.careful)) == .careful)
    }

    @Test func peekNeverLowersTheLevel() {
        #expect(ResultActionPlan.effectiveLevel(.likelyScam, peek: Self.report(.normal)) == .likelyScam)
        #expect(ResultActionPlan.effectiveLevel(.careful, peek: nil) == .careful)
    }

    @Test func openAsksFirstOnceAPeekSaysLikelyScam() {
        let tls = Self.report(.likelyScam, signals: [Signal(.tlsProblem)])
        #expect(ResultActionPlan.needsOpenConfirmation(offline: .normal, peek: tls))
        #expect(!ResultActionPlan.needsOpenConfirmation(offline: .normal, peek: Self.report(.careful)))
        #expect(!ResultActionPlan.needsOpenConfirmation(offline: .normal, peek: nil))
        #expect(ResultActionPlan.needsOpenConfirmation(offline: .likelyScam, peek: nil))
    }

    @Test func aRaisedVerdictLeadsWithPeekInTheLayout() {
        let actions: [ResultAction] = [.peek, .open, .copy, .share]
        let level = ResultActionPlan.effectiveLevel(.normal, peek: Self.report(.likelyScam))
        #expect(ResultActionPlan.layout(actions, level: level).primary == .peek)
    }

    @Test func knownOfferHidesWhenPeekFindsSomething() {
        #expect(!ResultActionPlan.peekBlocksKnownOffer(nil, offline: .careful))
        #expect(!ResultActionPlan.peekBlocksKnownOffer(Self.report(.careful, signals: [Signal(.ageUnknown)]), offline: .careful))
        #expect(ResultActionPlan.peekBlocksKnownOffer(Self.report(.careful, signals: [Signal(.scriptRedirect)]), offline: .careful))
        #expect(ResultActionPlan.peekBlocksKnownOffer(Self.report(.likelyScam), offline: .careful))
    }

    @Test func peekReasonsIncludeItsSignalsAndRaisingRedirects() {
        let original = ResultSamples.link("https://bit.example/x", host: "bit.example", domain: "bit.example",
                                          level: .careful, signals: [Signal(.shortener)])
        let firstHop = ResultSamples.link("https://bit.example/x", host: "bit.example", domain: "bit.example",
                                          level: .careful, signals: [Signal(.shortener)])
        let landing = ResultSamples.link("https://paypal-verify.example/", host: "paypal-verify.example",
                                         domain: "paypal-verify.example", level: .likelyScam,
                                         signals: [Signal(.brandInDomain, ["brand": "PayPal"])])
        let report = Self.report(.likelyScam, signals: [Signal(.domainVeryNew, ["days": "3"])], hops: [firstHop, landing])
        let rows = ResultReasons.peekRows(report, original: original)
        let brand = SignalText.sentence(for: Signal(.brandInDomain, ["brand": "PayPal"]))
        #expect(rows.first?.sentence == SignalText.sentence(for: Signal(.domainVeryNew, ["days": "3"])))
        #expect(rows.contains { $0.sentence.contains("paypal-verify.example") && $0.sentence.contains(brand) && !$0.isInfo })
        // The scanned link's own hop isn't repeated.
        #expect(!rows.contains { $0.sentence.contains(SignalText.sentence(for: Signal(.shortener))) })
    }

    @Test func aLinkInsideATextExplainsTheVerdictAtTheTop() {
        let inner = ResultSamples.scamLink
        let text = Analysis(code: ScannedCode(text: "Pay here http://login.paypa1-verify.example/account?id=1"),
                            payload: .text(TextInfo(text: "Pay here")), signals: [], embedded: [inner], level: .likelyScam)
        let rows = ResultReasons.rows(for: text)
        let domain = ResultEmbeddedList.domain(of: inner)
        #expect(rows.count == inner.signals.count)
        for signal in inner.signals {
            #expect(rows.contains { $0.sentence == ResultReasons.inLink(domain, SignalText.sentence(for: signal)) })
        }
    }

    @Test func infoSignalsOfInnerLinksStayInTheirDetail() {
        let inner = ResultSamples.link("www.example.org", host: "www.example.org", domain: "example.org",
                                       signals: [Signal(.assumedHttps)])
        let contact = Analysis(code: ScannedCode(text: "BEGIN:VCARD"), payload: ResultSamples.contact.payload,
                               signals: [], embedded: [inner], level: .normal)
        #expect(ResultReasons.rows(for: contact).isEmpty)
    }

    @Test func ownSignalsComeFirstAndRepeatsAppearOnce() {
        let a = ResultSamples.scamLink
        let rows = ResultReasons.rows(for: a, peek: Self.report(.likelyScam, signals: [Signal(.plainHttp)]))
        #expect(rows.map(\.sentence) == a.signals.map(SignalText.sentence(for:)))
    }

    @Test func peekIsOfferedOnlyWhenTheCoreSaysItCan() {
        let device = ResultCapabilities()
        let link = ResultSamples.normalLink.payload
        #expect(ResultActionPlan.actions(for: link, capabilities: device, canPeek: false) == [.open, .copy, .share, .makeCode])
        #expect(ResultActionPlan.actions(for: link, capabilities: device, canPeek: true).prefix(2) == [.peek, .open])
    }

    @Test func linksInsideAreWorstFirstAndCapped() {
        let normal = (0..<12).map { ResultSamples.link("https://n\($0).example/", host: "n\($0).example", domain: "n\($0).example") }
        let scam = ResultSamples.scamLink
        let careful = ResultSamples.link("http://c.example/", host: "c.example", domain: "c.example", level: .careful,
                                         signals: [Signal(.plainHttp)], scheme: "http")
        let all = [normal[0], careful] + normal.dropFirst() + [scam]
        let ordered = ResultEmbeddedList.ordered(all)
        #expect(ordered.first == scam)
        #expect(ordered[1] == careful)
        #expect(Array(ordered.dropFirst(2)) == normal)          // same verdict keeps its order
        let shown = ResultEmbeddedList.visible(all, showsAll: false)
        #expect(shown.count == ResultEmbeddedList.visibleLimit)
        #expect(shown.first == scam)
        #expect(ResultEmbeddedList.visible(all, showsAll: true).count == all.count)
        #expect(ResultEmbeddedList.visible(Array(normal.prefix(3)), showsAll: false).count == 3)
    }
}
