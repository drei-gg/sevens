import Foundation
import Testing
@testable import QRCore

private func sniff(_ html: String, contentType: String? = "text/html; charset=utf-8") -> HTMLSniff {
    HTMLSniffer.sniff(body: Array(html.utf8), contentType: contentType)
}

@Suite struct PeekHTMLSnifferTests {
    @Test func titleDecodesEntitiesAndCollapsesWhitespace() {
        let r = sniff("<TITLE>\n  Tom &amp; Jerry&nbsp;&#169; &#x41;\t&lt;ok&gt; &bogus; </title>")
        #expect(r.title == "Tom & Jerry\u{A0}\u{A9} A <ok> &bogus;".replacingOccurrences(of: "\u{A0}", with: " "))
    }

    @Test func titleCapsAt200() {
        let r = sniff("<title>" + String(repeating: "a", count: 500) + "</title>")
        #expect(r.title?.count == 200)
    }

    @Test func emptyOrMissingTitleIsNil() {
        #expect(sniff("<title>  </title>").title == nil)
        #expect(sniff("<p>hi</p>").title == nil)
    }

    @Test func formsAndInputsWithAllQuoteStyles() {
        let r = sniff("""
        <FORM ACTION='/a?x=1&amp;y=2'><form><form action=https://o.example/p>
        <input type=password NAME="pw" id='p1' Autocomplete="current-password" placeholder=Secret>
        <input name=q>
        """)
        #expect(r.formActions == ["/a?x=1&y=2", nil, "https://o.example/p"])
        #expect(r.inputs.count == 2)
        #expect(r.inputs[0] == .init(type: "password", name: "pw", id: "p1", autocomplete: "current-password", placeholder: "Secret"))
        #expect(r.inputs[1] == .init(name: "q"))
    }

    @Test func metaRefreshForms() {
        #expect(sniff(#"<meta http-equiv="refresh" content="5; url=https://a.example/x">"#).metaRefreshURL == "https://a.example/x")
        #expect(sniff(#"<META HTTP-EQUIV=REFRESH CONTENT="0;URL='/next'">"#).metaRefreshURL == "/next")
        #expect(sniff(#"<meta http-equiv="refresh" content="0; https://b.example">"#).metaRefreshURL == "https://b.example")
        #expect(sniff(#"<meta http-equiv="refresh" content="30">"#).metaRefreshURL == nil)
        #expect(sniff(#"<meta name="x" content="5; url=/y">"#).metaRefreshURL == nil)
    }

    @Test func scriptRedirectPatterns() {
        for js in ["location.href='/x'", "location.replace('/x')", "location.assign('/x')", "window.location = '/x'",
                   "window.location='/x'", "top.location.href", "LOCATION.HREF = 1"] {
            #expect(sniff("<script>\(js)</script>").scriptRedirect, "\(js)")
        }
        #expect(!sniff("<script>if (window.location == 'a') {}</script>").scriptRedirect)
        #expect(!sniff("<script>var a = 1;</script>").scriptRedirect)
        #expect(!sniff(#"<script src="location.href"></script>"#).scriptRedirect)
        #expect(!sniff("<p>location.href</p>").scriptRedirect)
    }

    @Test func scriptCountAndVisibleText() {
        let r = sniff("""
        <html><head><style>p { color: red }</style><script>var a = "not shown";</script><script src=x.js></script></head>
        <!-- <script>location.href='/hidden'</script> hidden comment text -->
        <body>Hello   <b>big</b>
        world&nbsp;&amp; <noscript>enable JS</noscript></body></html>
        """)
        #expect(r.scriptCount == 2)
        #expect(!r.scriptRedirect)
        #expect(r.visibleTextLength == "Hello big world & enable JS".count)
    }

    @Test func commentsAreIgnoredEverywhere() {
        let r = sniff("<!-- <title>no</title><form action='/x'><input type=password> --><title>yes</title>")
        #expect(r.title == "yes")
        #expect(r.formActions.isEmpty)
        #expect(r.inputs.isEmpty)
    }

    @Test func cutMidTagTerminatesWithoutCrash() {
        let pieces = ["<form action=\"/x", "<input type=\"pass", "<script>location.", "<!-- open", "<title>abc", "<a href='",
                      "<", "</", "<!", "<meta http-equiv=refresh content=\"0; url=", "<p attr", "<p attr=", "<p a=b"]
        for p in pieces { _ = sniff("<p>ok</p>" + p) }
        #expect(sniff("<title>ok</title><form action=\"/x").title == "ok")
        #expect(sniff("<p>ok</p><script>location.").scriptRedirect == false)
    }

    @Test func emptyAndGarbageBodies() {
        #expect(HTMLSniffer.sniff(body: [], contentType: nil) == HTMLSniff())
        _ = HTMLSniffer.sniff(body: (0..<5000).map { UInt8(truncatingIfNeeded: $0 &* 31) }, contentType: "text/html; charset=bogus")
    }

    @Test func windows1252ByHeader() {
        let bytes: [UInt8] = Array("<title>caf".utf8) + [0xE9, 0x20, 0x80, 0x20, 0x93] + Array("x".utf8) + [0x94] + Array("</title>".utf8)
        let r = HTMLSniffer.sniff(body: bytes, contentType: "text/html; Charset=\"windows-1252\"")
        #expect(r.title == "caf\u{E9} \u{20AC} \u{201C}x\u{201D}")
        #expect(HTMLSniffer.sniff(body: bytes, contentType: "text/html; charset=ISO-8859-1").title == r.title)
    }

    @Test func windows1252ByMetaInFirst1024Bytes() {
        let bytes: [UInt8] = Array(#"<meta charset=windows-1252><title>na"#.utf8) + [0xEF] + Array("ve</title>".utf8)
        #expect(HTMLSniffer.sniff(body: bytes, contentType: "text/html").title == "na\u{EF}ve")
        let http: [UInt8] = Array(#"<meta http-equiv="Content-Type" content="text/html; charset=iso-8859-1"><title>"#.utf8) + [0xFC] + Array("</title>".utf8)
        #expect(HTMLSniffer.sniff(body: http, contentType: nil).title == "\u{FC}")
        // Past the first 1024 bytes the meta is not looked at: UTF-8 with replacement.
        let late: [UInt8] = Array(String(repeating: " ", count: 1100).utf8) + Array("<meta charset=windows-1252><title>".utf8) + [0xE9] + Array("</title>".utf8)
        #expect(HTMLSniffer.sniff(body: late, contentType: nil).title == "\u{FFFD}")
    }

    @Test func headerCharsetBeatsMeta() {
        let bytes: [UInt8] = Array("<meta charset=windows-1252><title>".utf8) + [0xC3, 0xA9] + Array("</title>".utf8)
        #expect(HTMLSniffer.sniff(body: bytes, contentType: "text/html; charset=utf-8").title == "\u{E9}")
    }

    @Test func utf16WithBOM() {
        let le: [UInt8] = [0xFF, 0xFE] + "<title>Hi \u{20AC}</title>".utf16.flatMap { [UInt8($0 & 0xFF), UInt8($0 >> 8)] }
        let be: [UInt8] = [0xFE, 0xFF] + "<title>Hi \u{20AC}</title>".utf16.flatMap { [UInt8($0 >> 8), UInt8($0 & 0xFF)] }
        #expect(HTMLSniffer.sniff(body: le, contentType: "text/html").title == "Hi \u{20AC}")
        #expect(HTMLSniffer.sniff(body: be, contentType: "text/html; charset=utf-8").title == "Hi \u{20AC}")
    }

    @Test func utf8BOMIsDropped() {
        let bytes: [UInt8] = [0xEF, 0xBB, 0xBF] + Array("<title>x</title>".utf8)
        #expect(HTMLSniffer.sniff(body: bytes, contentType: nil).title == "x")
    }

    @Test func invalidUTF8GetsReplacementAndShiftJISDoesNotCrash() {
        let bad: [UInt8] = Array("<title>a".utf8) + [0xFF, 0xFE, 0xFD] + Array("b</title>".utf8)
        #expect(HTMLSniffer.sniff(body: bad, contentType: nil).title?.hasPrefix("a") == true)
        let sjis: [UInt8] = Array("<meta charset=shift_jis><title>".utf8) + [0x83, 0x65, 0x83, 0x58, 0x83, 0x67] + Array("</title>".utf8)
        _ = HTMLSniffer.sniff(body: sjis, contentType: nil).title
    }

    @Test func bodyCutAt512KBMidTag() {
        let head = "<html><head><title>Big page</title></head><body>"
        let filler = "<p>filler paragraph with some words in it</p>\n"
        var html = head
        while html.utf8.count < 512 * 1024 - 40 { html += filler }
        html += "<form action=\"https://evil.example/collect\"><input type=\"passw"
        var bytes = Array(html.utf8)
        bytes.append(contentsOf: Array(repeating: 0x61, count: 4000))   // beyond the cap
        let r = HTMLSniffer.sniff(body: bytes, contentType: "text/html")
        #expect(r.title == "Big page")
        #expect(r.inputs.isEmpty)
        #expect(r.visibleTextLength > 100_000)
    }

    @Test func manyUnclosedTagsStayLinear() {
        let bytes = Array(String(repeating: "<a ", count: 150_000).utf8)
        let r = HTMLSniffer.sniff(body: bytes, contentType: nil)
        #expect(r.scriptCount == 0)
        let lt = HTMLSniffer.sniff(body: Array(String(repeating: "<script>", count: 60_000).utf8), contentType: nil)
        #expect(lt.scriptCount == 1)
    }
}
