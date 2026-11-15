import Foundation
import Testing
@testable import QRCore

private func item(_ payload: String = "https://example.com", kind: HistoryItem.Kind = .scanned,
                  type: String = "link", level: Level = .normal, signals: [String] = [],
                  at t: TimeInterval = 1_700_000_000, note: String? = nil,
                  encoding: HistoryItem.PayloadEncoding = .text) -> HistoryItem {
    HistoryItem(kind: kind, payload: payload, payloadEncoding: encoding, symbology: .qr, typeID: type,
                level: level, signalIDs: signals, date: Date(timeIntervalSince1970: t), note: note)
}

@Suite struct HistoryFileTests {
    @Test func roundTrip() throws {
        let items = [item(note: "n"), item("héllo 🙂", kind: .made, type: "text", level: .careful, signals: ["ipHost"], at: 1_700_000_100),
                     item("AAEC", at: 1_700_000_200, encoding: .base64)]
        let data = try HistoryFile.encode(items)
        #expect(HistoryFile.decode(data) == .items(items))
        let json = try #require(String(data: data, encoding: .utf8))
        #expect(json.contains("\"schema\":1"))
        #expect(json.contains("\"level\":0"))
        #expect(!json.contains("\n"))
        #expect(try HistoryFile.encode(items) == data)
    }

    @Test func emptyRoundTrip() throws {
        #expect(HistoryFile.decode(try HistoryFile.encode([])) == .items([]))
    }

    @Test func unknownSchemaIsNotDecodedFurther() {
        let data = Data(#"{"schema":2,"items":"whatever shape"}"#.utf8)
        #expect(HistoryFile.decode(data) == .unknownSchema(2))
        #expect(HistoryFile.decode(Data(#"{"schema":0,"items":[]}"#.utf8)) == .unknownSchema(0))
    }

    @Test func garbageIsUnreadable() {
        for text in ["", "not json", "[]", "42", "null", "{}", #"{"schema":"1","items":[]}"#,
                     #"{"schema":1}"#, #"{"schema":1,"items":{}}"#, #"{"schema":1.5,"items":[]}"#] {
            #expect(HistoryFile.decode(Data(text.utf8)) == .unreadable, "\(text)")
        }
    }

    @Test func oneCorruptItemKeepsTheRest() throws {
        let good1 = item("one"), good2 = item("two", at: 1_700_000_500)
        var file = try #require(String(data: HistoryFile.encode([good1, good2]), encoding: .utf8))
        let marker = try #require(file.range(of: "\"items\":["))
        file.insert(contentsOf: #"{"id":"nope","kind":"scanned"},7,null,{"kind":"weird"},"#, at: marker.upperBound)
        #expect(HistoryFile.decode(Data(file.utf8)) == .items([good1, good2]))
    }

    @Test func fractionalSecondDatesDecode() {
        let json = """
        {"schema":1,"items":[{"id":"3F2504E0-4F89-11D3-9A0C-0305E82C3301","kind":"made","payload":"x",
        "payloadEncoding":"text","symbology":"qr","typeID":"text","level":1,"signalIDs":[],
        "date":"2023-11-14T22:13:20.250Z"}]}
        """
        guard case .items(let items) = HistoryFile.decode(Data(json.utf8)), items.count == 1 else {
            Issue.record("did not decode"); return
        }
        #expect(abs(items[0].date.timeIntervalSince1970 - 1_700_000_000.25) < 0.001)
        #expect(items[0].note == nil)
    }

    @Test func unknownSignalIDsSurvive() throws {
        let items = [item(signals: ["someFutureSignal"])]
        #expect(HistoryFile.decode(try HistoryFile.encode(items)) == .items(items))
    }
}

@Suite struct HistorySearchTests {
    let a = item("https://Example.com/Pay", type: "link", at: 100, note: "Lunch")
    let b = item("WIFI:S:Café;;", type: "wifi", at: 300)
    let c = item("hello", type: "text", at: 200, note: "see Café menu")

    @Test func emptyQueryReturnsAllNewestFirst() {
        #expect(HistoryFile.search([a, b, c], query: "").map(\.payload) == [b.payload, c.payload, a.payload])
        #expect(HistoryFile.search([a, b, c], query: "  \n ").count == 3)
    }

    @Test func caseInsensitiveOverPayloadNoteAndType() {
        #expect(HistoryFile.search([a, b, c], query: "EXAMPLE") == [a])
        #expect(HistoryFile.search([a, b, c], query: "lunch") == [a])
        #expect(HistoryFile.search([a, b, c], query: "WiFi") == [b])
        #expect(HistoryFile.search([a, b, c], query: "nothing") == [])
    }

    @Test func diacriticsAndOrder() {
        #expect(HistoryFile.search([a, b, c], query: "cafe") == [b, c])
        #expect(HistoryFile.search([a, b, c], query: " café ") == [b, c])
    }

    @Test func equalDatesKeepInputOrder() {
        let x = item("x1", at: 5), y = item("x2", at: 5)
        #expect(HistoryFile.search([x, y], query: "x") == [x, y])
    }
}

@Suite struct HistoryExportTests {
    @Test func jsonIsTheFileShape() throws {
        let items = [item()]
        #expect(try HistoryExport.json(items) == HistoryFile.encode(items))
    }

    @Test func csvHeaderRowsAndCRLF() {
        let i = HistoryItem(id: UUID(uuidString: "3F2504E0-4F89-11D3-9A0C-0305E82C3301")!, kind: .scanned,
                            payload: "https://x.test", symbology: .qr, typeID: "link", level: .likelyScam,
                            signalIDs: ["ipHost", "plainHTTP"], date: Date(timeIntervalSince1970: 1_700_000_000), note: nil)
        let csv = HistoryExport.csv([i])
        #expect(csv == "date,kind,type,level,payload,note,signals,symbology,id\r\n"
            + "2023-11-14T22:13:20Z,scanned,link,likelyScam,https://x.test,,ipHost plainHTTP,qr,3F2504E0-4F89-11D3-9A0C-0305E82C3301\r\n")
        #expect(HistoryExport.csv([]) == "date,kind,type,level,payload,note,signals,symbology,id\r\n")
    }

    @Test func csvQuoting() {
        let csv = HistoryExport.csv([item("a,b \"q\"\r\nline2", note: "x\ny")])
        #expect(csv.contains("\"a,b \"\"q\"\"\r\nline2\""))
        #expect(csv.contains("\"x\ny\""))
    }

    @Test(arguments: ["=1+1", "+1", "-2", "@SUM(A1)"])
    func csvFormulaGuard(_ payload: String) {
        let csv = HistoryExport.csv([item(payload, note: payload)])
        #expect(csv.contains(",'\(payload),'\(payload),"))
    }

    @Test func csvFormulaGuardThenQuoting() {
        #expect(HistoryExport.csv([item("=a,b")]).contains("\"'=a,b\""))
    }

    @Test func csvUnicodeAndBase64StayAsStored() {
        let csv = HistoryExport.csv([item("日本語 🙂 é"), item("AAEC/w==", encoding: .base64)])
        #expect(csv.contains(",日本語 🙂 é,"))
        #expect(csv.contains(",AAEC/w==,"))
    }
}

@Suite struct HistoryItemTests {
    @Test func initFromAnalysisText() {
        let embedded = Analysis(code: ScannedCode(text: "https://b.test"), payload: .text(TextInfo(text: "b")),
                                signals: [Signal(.plainHTTP), Signal(.ipHost), Signal(.assumedHTTPS)],
                                embedded: [], level: .careful)
        let top = Analysis(code: ScannedCode(text: "see https://b.test", symbology: .aztec),
                           payload: .text(TextInfo(text: "see")),
                           signals: [Signal(.openWiFi), Signal(.peekIncomplete), Signal(.plainHTTP)],
                           embedded: [embedded], level: .careful)
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        let h = HistoryItem(analysis: top, kind: .made, date: date)
        #expect(h.payload == "see https://b.test")
        #expect(h.payloadEncoding == .text)
        #expect(h.symbology == .aztec)
        #expect(h.typeID == "text")
        #expect(h.level == .careful)
        #expect(h.kind == .made)
        #expect(h.date == date)
        #expect(h.note == nil)
        #expect(h.signalIDs == ["openWiFi", "plainHTTP", "ipHost"])
        #expect(h.scannedCode == top.code)
        #expect(HistoryItem(analysis: top, kind: .made, date: date).id != h.id)
    }

    @Test func initFromAnalysisBinary() {
        let bytes: [UInt8] = [0xFF, 0x00, 0xFE, 0x80]
        let a = Analysis(code: ScannedCode(text: nil, bytes: bytes, symbology: .pdf417), payload: .binary(byteCount: 4),
                         signals: [], embedded: [], level: .normal)
        let h = HistoryItem(analysis: a, kind: .scanned, date: Date(timeIntervalSince1970: 0))
        #expect(h.payloadEncoding == .base64)
        #expect(h.payload == Data(bytes).base64EncodedString())
        #expect(h.typeID == "binary")
        #expect(h.scannedCode == ScannedCode(text: nil, bytes: bytes, symbology: .pdf417))
    }

    @Test func scannedCodeFromBase64() {
        var h = item(Data("hi 🙂".utf8).base64EncodedString(), encoding: .base64)
        #expect(h.scannedCode.text == "hi 🙂")
        #expect(h.scannedCode.bytes == Array("hi 🙂".utf8))
        h.payload = "!!!not base64!!!"
        #expect(h.scannedCode.bytes.isEmpty)
        #expect(h.scannedCode.text == nil)
    }
}
