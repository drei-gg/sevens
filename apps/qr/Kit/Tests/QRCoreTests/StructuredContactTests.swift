import Testing
@testable import QRCore

@Suite struct StructuredContactTests {
    @Test func folding() {
        let c = ContactParser.parse("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ann\r\nNOTE:one\r\n two\r\n\tthree\r\nEND:VCARD")
        #expect(c?.note == "onetwothree")
    }

    @Test func quotedPrintableCharsets() {
        let t = "BEGIN:VCARD\nVERSION:2.1\nFN;ENCODING=QUOTED-PRINTABLE;CHARSET=ISO-8859-1:Ren=E9=\ne\nORG;QUOTED-PRINTABLE;CHARSET=UTF-8:=F0=9F=98=80 Co\nTITLE;ENCODING=QUOTED-PRINTABLE:caf=E9\nEND:VCARD"
        let c = ContactParser.parse(t)
        #expect(c?.name == "Renée")
        #expect(c?.organization == "😀 Co")
        #expect(c?.title == "café")  // no charset, not UTF-8: Windows-1252
    }

    @Test func textEscapesGroupsAndMultiples() {
        let t = "BEGIN:VCARD\nVERSION:4.0\nitem2.EMAIL;type=work:a@x.org\nEMAIL:b@x.org\nTEL:1\nTEL:2\nORG:A\\;B;Dept\nNOTE:x\\ny\\, z\\\\\nN:;Solo;;;\nEND:VCARD"
        let c = ContactParser.parse(t)
        #expect(c?.emails == ["a@x.org", "b@x.org"])
        #expect(c?.phones == ["1", "2"])
        #expect(c?.organization == "A;B")
        #expect(c?.note == "x\ny, z\\")
        #expect(c?.name == "Solo")
        #expect(c?.familyName == nil)
    }

    @Test func addressOrderAndLinks() {
        let c = ContactParser.parse("BEGIN:VCARD\nADR:;;Street 1;Town;;;DE\nURL:a.example\nURL:b.example\nEND:VCARD")
        #expect(c?.addresses == ["Street 1\nTown\nDE"])
        #expect(c?.links == ["a.example", "b.example"])
    }

    @Test func rejectsNonCards() {
        #expect(ContactParser.parse("FN:Ann") == nil)
        #expect(ContactParser.parse("BEGIN:VEVENT\nEND:VEVENT") == nil)
        #expect(ContactParser.parse("hello") == nil)
    }

    @Test func meCard() {
        let c = ContactParser.parse(#"MECARD:N:Doe,John;TEL:1;TEL:2;ADR:a\, b,c;NOTE:x\:y;;"#)
        #expect(c?.name == "John Doe")
        #expect(c?.phones == ["1", "2"])
        #expect(c?.addresses == ["a, b\nc"])
        #expect(c?.note == "x:y")
    }

    @Test func photoDataIsIgnored() {
        let t = "BEGIN:VCARD\nVERSION:2.1\nFN:P\nPHOTO;ENCODING=BASE64;JPEG:/9j/4AAQSkZJRgABAQ\n AAAAAAAAAAAAAAAAAAAA\n\nTEL:5\nEND:VCARD"
        let c = ContactParser.parse(t)
        #expect(c?.name == "P")
        #expect(c?.phones == ["5"])
    }
}
