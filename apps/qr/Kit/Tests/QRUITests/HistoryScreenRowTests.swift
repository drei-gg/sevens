import Foundation
import Testing
import QRCore
@testable import QRUI

@Suite struct HistoryScreenRowTests {
    private func item(_ payload: String, typeID: String = "text", encoding: HistoryItem.PayloadEncoding = .text) -> HistoryItem {
        HistoryItem(kind: .scanned, payload: payload, payloadEncoding: encoding, symbology: .qr, typeID: typeID,
                    level: .normal, signalIDs: [], date: Date(timeIntervalSince1970: 0))
    }

    @Test func linkShowsOnlyTheHost() {
        #expect(HistoryRowSummary.shortPayload(for: item("https://Example.ORG/menu?x=1#top", typeID: "link")) == "example.org")
    }

    @Test func linkHostDropsUserinfoAndPort() {
        #expect(HistoryRowSummary.host(in: "https://paypal.com@evil.example:8443/login") == "evil.example")
        #expect(HistoryRowSummary.host(in: "http://[2001:db8::1]:80/x") == "[2001:db8::1]")
    }

    @Test func hostNeedsASchemeAndAHost() {
        #expect(HistoryRowSummary.host(in: "example.org/menu") == nil)
        #expect(HistoryRowSummary.host(in: "https:///path") == nil)
        #expect(HistoryRowSummary.host(in: "1http://x.example") == nil)
    }

    @Test func linkWithoutHostFallsBackToText() {
        #expect(HistoryRowSummary.shortPayload(for: item("not a url", typeID: "link")) == "not a url")
    }

    @Test func longTextIsCutWithEllipsis() {
        let text = String(repeating: "a", count: 200)
        let short = HistoryRowSummary.shortPayload(for: item(text))
        #expect(short.count == HistoryRowSummary.maxPayloadLength)
        #expect(short.hasSuffix("…"))
    }

    @Test func controlCharactersAndNewlinesBecomeSpaces() {
        #expect(HistoryRowSummary.shortPayload(for: item("line one\n\nline\ttwo\u{0}x")) == "line one line two x")
    }

    @Test func binaryPayloadIsDescribedNotDumped() {
        let data = Data([1, 2, 3, 4]).base64EncodedString()
        let text = HistoryRowSummary.shortPayload(for: item(data, typeID: "binary", encoding: .base64))
        #expect(text.contains("4"))
        #expect(!text.contains(data))
    }

    @Test func notePreviewIsNilForBlankAndCutWhenLong() {
        #expect(HistoryRowSummary.notePreview(nil) == nil)
        #expect(HistoryRowSummary.notePreview("  \n ") == nil)
        #expect(HistoryRowSummary.notePreview(String(repeating: "n", count: 500))?.count == HistoryRowSummary.maxNoteLength)
    }

    @Test func verdictSignalsSkipUnknownIDsAndKeepOrder() {
        let signals = HistoryVerdictSignals.signals(from: ["plainHTTP", "fromTheFuture", "lookalikeBrand"])
        #expect(signals.map(\.id.rawValue) == ["lookalikeBrand", "plainHTTP"])
    }

    @Test func noteChangeIgnoresSurroundingWhitespace() {
        #expect(!HistoryNote.isChanged(draft: " hi ", saved: "hi"))
        #expect(!HistoryNote.isChanged(draft: "", saved: nil))
        #expect(HistoryNote.isChanged(draft: "hi!", saved: "hi"))
    }
}
