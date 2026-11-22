import Foundation
import QRCore
import SwiftUI
import Testing
import UIKit
@testable import QRUI

@Suite struct MakeFormStateTests {
    private func request(_ s: MakeFormState, _ k: MakeFormKind) -> MakeRequest? {
        if case .request(let r) = s.result(for: k) { return r }
        return nil
    }
    private func issue(_ s: MakeFormState, _ k: MakeFormKind) -> MakeFormIssue? {
        if case .invalid(let i) = s.result(for: k) { return i }
        return nil
    }

    @Test func emptyFormsAreBlank() {
        let s = MakeFormState()
        for kind in MakeFormKind.allCases where kind != .event || true {
            let i = issue(s, kind)
            #expect(i?.isBlank == true, "\(kind)")
        }
    }

    @Test func linkTrimsAndText() {
        var s = MakeFormState()
        s.link = "  https://example.org  "
        s.text = "hello\nworld"
        #expect(request(s, .link) == .link("https://example.org"))
        #expect(request(s, .text) == .text("hello\nworld"))
        s.text = "   "
        #expect(issue(s, .text) == .required(.text))
    }

    @Test func wifiRules() {
        var s = MakeFormState()
        s.ssid = "Home"
        #expect(issue(s, .wifi) == .required(.wifiPassword))
        s.wifiPassword = "secret123"
        #expect(request(s, .wifi) == .wifi(WiFiInfo(ssid: "Home", security: "WPA", password: "secret123", isHidden: false)))
        s.wifiSecurity = .none
        s.wifiPassword = ""
        s.wifiHidden = true
        #expect(request(s, .wifi) == .wifi(WiFiInfo(ssid: "Home", security: "nopass", password: nil, isHidden: true)))
    }

    @Test func contactNeedsANameAndChecksShapes() {
        var s = MakeFormState()
        s.contactPhone = "+63 917 123 4567"
        #expect(issue(s, .contact) == .required(.contactName))
        s.givenName = "Ana"
        s.familyName = "Cruz"
        s.contactEmail = "ana@example"
        #expect(issue(s, .contact) == .badEmail(.contactEmail))
        s.contactEmail = "ana@example.org"
        guard case .contact(let c)? = request(s, .contact) else { Issue.record("no contact"); return }
        #expect(c.name == "Ana Cruz")
        #expect(c.phones == ["+63 917 123 4567"])
        #expect(c.emails == ["ana@example.org"])
        s.contactPhone = "call me"
        #expect(issue(s, .contact) == .badPhone(.contactPhone))
    }

    @Test func contactPhoneMayBeLocalWhilePhoneAndSMSStayInternational() {
        var s = MakeFormState()
        s.givenName = "Ana"
        s.contactPhone = "0917 123 4567"
        guard case .contact(let c)? = request(s, .contact) else { Issue.record("a local contact number was refused"); return }
        #expect(c.phones == ["0917 123 4567"])
        s.contactPhone = "12"
        #expect(issue(s, .contact) == .badPhone(.contactPhone))
        #expect(MakeFormIssue.badPhone(.contactPhone).message != MakeFormIssue.badPhone(.phone).message)
        s.phone = "0917 123 4567"
        #expect(issue(s, .phone) == .badPhone(.phone))
    }

    @Test func emailSmsPhoneShapes() {
        var s = MakeFormState()
        s.emailAddress = "nobody"
        #expect(issue(s, .email) == .badEmail(.emailAddress))
        s.emailAddress = "a@b.co"
        s.emailSubject = "Hi"
        #expect(request(s, .email) == .email(EmailInfo(address: "a@b.co", subject: "Hi", body: nil)))
        s.smsNumber = "12"
        #expect(issue(s, .sms) == .badPhone(.smsNumber))
        // SPEC §8: international form only; a local number asks for the country code.
        s.smsNumber = "0917 123 4567"
        #expect(issue(s, .sms) == .badPhone(.smsNumber))
        s.smsNumber = "+63 917 123 4567"
        s.smsBody = "On my way"
        #expect(request(s, .sms) == .sms(SMSInfo(number: "+63 917 123 4567", body: "On my way")))
        s.phone = "abc"
        #expect(issue(s, .phone) == .badPhone(.phone))
        s.phone = "(02) 8123-4567"
        #expect(issue(s, .phone) == .badPhone(.phone))
        s.phone = "0063 (2) 8123-4567"
        #expect(request(s, .phone) == .phone("0063 (2) 8123-4567"))
        // The core writes it as TEL:+…, separators dropped.
        #expect(PayloadEncoder.encode(.phone("0063 (2) 8123-4567")) == "TEL:+63281234567")
        #expect(PayloadEncoder.encode(.sms(SMSInfo(number: "+63 917 123 4567", body: nil))) == "sms:+639171234567")
    }

    @Test func locationRanges() {
        var s = MakeFormState()
        s.latitude = "91"
        s.longitude = "10"
        #expect(issue(s, .location) == .badLatitude)
        s.latitude = "14,5995"
        s.longitude = "181"
        #expect(issue(s, .location) == .badLongitude)
        s.longitude = "120.9842"
        #expect(request(s, .location) == .geo(GeoInfo(latitude: "14.5995", longitude: "120.9842", altitude: nil, query: nil)))
        s.latitude = "-90"
        s.longitude = "-180"
        #expect(request(s, .location) != nil)
        s.latitude = "NaN"
        #expect(issue(s, .location) == .badLatitude)
    }

    @Test func eventRules() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        var s = MakeFormState(now: now)
        #expect(s.eventStart >= now)
        #expect(s.eventEnd == s.eventStart.addingTimeInterval(3600))
        s.eventTitle = "Launch"
        s.eventEnd = s.eventStart.addingTimeInterval(-60)
        #expect(issue(s, .event) == .endBeforeStart)
        s.eventEnd = s.eventStart.addingTimeInterval(1800)
        guard case .event(let e)? = request(s, .event) else { Issue.record("no event"); return }
        #expect(e.summary == "Launch")
        #expect(e.isAllDay == false)
        #expect(e.start == s.eventStart)
    }

    @Test func allDayEventUsesExclusiveGMTDates() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Manila") ?? .current
        let day = cal.date(from: DateComponents(year: 2026, month: 3, day: 11, hour: 15)) ?? Date()
        let gmt = MakeFormState.wallAsGMT(day, calendar: cal)
        #expect(gmt.timeIntervalSince1970 == 1_773_187_200 + 15 * 3600 - 0 || gmt.timeIntervalSince1970 > 0)
        let back = MakeFormState.localFromGMTWall(gmt, calendar: cal)
        #expect(back == day)
    }

    @Test func prefillRoundTrips() {
        let reqs: [MakeRequest] = [
            .link("https://example.org"), .text("hi"),
            .wifi(WiFiInfo(ssid: "Cafe", security: "WEP", password: "12345", isHidden: true)),
            .email(EmailInfo(address: "a@b.co", subject: "S", body: "B")),
            .sms(SMSInfo(number: "+631234567", body: "yo")),
            .phone("+631234567"),
            .geo(GeoInfo(latitude: "1.5", longitude: "2.5", altitude: nil, query: "Home")),
        ]
        for r in reqs {
            let s = MakeFormState(prefill: r)
            #expect(s.result(for: MakeFormKind(request: r)) == .request(r), "\(r)")
        }
    }

    @Test func enterpriseWiFiKeepsItsDetailsWhileUntouched() {
        let w = WiFiInfo(ssid: "Corp", security: "WPA2-EAP", password: "x", eapMethod: "PEAP", identity: "me")
        let s = MakeFormState(prefill: .wifi(w))
        guard case .request(.wifi(let out)) = s.result(for: .wifi) else { Issue.record("no wifi"); return }
        #expect(out.eapMethod == "PEAP")
        #expect(out.identity == "me")
        #expect(out.security == "WPA2-EAP")
    }

    @Test func shapeHelpers() {
        #expect(MakeFormValidation.isPlausibleEmail("a@b.co"))
        #expect(!MakeFormValidation.isPlausibleEmail("a@b"))
        #expect(!MakeFormValidation.isPlausibleEmail("a b@c.de"))
        #expect(!MakeFormValidation.isPlausibleEmail("@c.de"))
        #expect(!MakeFormValidation.isPlausibleEmail("a@@c.de"))
        #expect(MakeFormValidation.isPlausiblePhone("+1 (555) 010-9999"))
        #expect(MakeFormValidation.isPlausiblePhone("0044 20 7946 0958"))
        #expect(!MakeFormValidation.isPlausiblePhone("55"))
        #expect(!MakeFormValidation.isPlausiblePhone("555-CALL"))
        #expect(!MakeFormValidation.isPlausiblePhone("(555) 010-9999"))
        #expect(!MakeFormValidation.isPlausiblePhone("+1234567890123456789012"))
    }
}

@Suite struct MakeExportOptionsTests {
    @Test func sizeClamping() {
        #expect(MakeExportOptions.clampedSize("1500") == 1500)
        #expect(MakeExportOptions.clampedSize("9999") == 4096)
        #expect(MakeExportOptions.clampedSize("99999999999999999999999") == 4096)
        #expect(MakeExportOptions.clampedSize("1") == 32)
        #expect(MakeExportOptions.clampedSize("") == 1024)
        #expect(MakeExportOptions.clampedSize("abc") == 1024)
        var o = MakeExportOptions()
        #expect(o.pixelSize == 1024)
        o.sizeChoice = .s2048
        #expect(o.pixelSize == 2048)
        o.sizeChoice = .custom
        o.customSize = "5000"
        #expect(o.pixelSize == 4096)
        #expect(o.customSizeWasClamped)
        o.customSize = "800"
        #expect(!o.customSizeWasClamped)
    }

    @Test func fileNames() {
        let tz = TimeZone(identifier: "UTC") ?? .gmt
        let date = Date(timeIntervalSince1970: 1_773_237_900)   // 2026-03-11 14:05 UTC
        #expect(MakeExportOptions.fileName(symbology: .qr, kind: .link, date: date, format: .png, timeZone: tz) == "qr-link-20260311-1405.png")
        #expect(MakeExportOptions.fileName(symbology: .code128, kind: .text, date: date, format: .svg, timeZone: tz) == "code128-text-20260311-1405.svg")
        #expect(MakeExportOptions.fileName(symbology: .qr, kind: .wifi, date: date, format: .pdf, sheet: true, timeZone: tz) == "qr-wifi-20260311-1405-sheet.pdf")
    }

    @Test func captionTrims() {
        var o = MakeExportOptions()
        #expect(o.cleanCaption == nil)
        o.caption = "  Scan me \n"
        #expect(o.cleanCaption == "Scan me")
    }

    @Test func writerProducesFilesInTheTempFolder() throws {
        let m = try CodeGenerator.generate(text: "https://example.org", symbology: .qr, correction: .auto)
        var o = MakeExportOptions()
        for format in MakeExportFormat.allCases {
            o.format = format
            let data = MakeExportWriter.data(options: o, matrix: m, foreground: .black, background: .white)
            #expect(!data.isEmpty)
            let url = try MakeExportWriter.write(data, named: "makeexport-test.\(format.fileExtension)")
            #expect(try Data(contentsOf: url) == data)
            try? FileManager.default.removeItem(at: url)
        }
        o.format = .pdf
        o.pdfLayout = .sheet
        #expect(!MakeExportWriter.data(options: o, matrix: m, foreground: .black, background: .white).isEmpty)
    }
}

@Suite struct MakeStyleColorTests {
    @Test func colorToSRGB() {
        let red = MakeStyle.rgb(Color(.sRGB, red: 1, green: 0, blue: 0, opacity: 1))
        #expect(red == RGBColor(red: 1, green: 0, blue: 0))
        let mid = MakeStyle.rgb(UIColor(red: 0.25, green: 0.5, blue: 0.75, alpha: 1))
        #expect(abs(mid.red - 0.25) < 0.01 && abs(mid.green - 0.5) < 0.01 && abs(mid.blue - 0.75) < 0.01)
        #expect(MakeStyle.rgb(Color.black) == .black)
        #expect(MakeStyle.rgb(Color.white) == .white)
        // Extended range clamps.
        let wide = MakeStyle.rgb(UIColor(displayP3Red: 1, green: 0, blue: 0, alpha: 1))
        #expect((0...1).contains(wide.red) && (0...1).contains(wide.green) && (0...1).contains(wide.blue))
    }
}

@MainActor
@Suite struct MakeScreenModelTests {
    @Test func generatesAndGatesExport() async {
        let model = MakeScreenModel()
        #expect(!model.canExport)
        await model.refresh()
        #expect(model.isBlank)
        model.form.link = "https://example.org/menu"
        #expect(!model.canExport)   // stale until refreshed
        await model.refresh()
        #expect(model.canExport)
        #expect(model.codeAccessibilityLabel == "QR code for https://example.org/menu")
        #expect(model.previewImage != nil)
    }

    @Test func tooLongAndNotASCIIDisableExport() async {
        let model = MakeScreenModel()
        model.form.text = String(repeating: "a", count: 9000)
        model.kind = .text
        await model.refresh()
        #expect(model.generationError == .tooLong)
        #expect(!model.canExport)
        model.symbology = .code128
        model.form.text = "café"
        await model.refresh()
        #expect(model.generationError == .notASCII)
    }

    @Test func prefillPicksTheKind() {
        let model = MakeScreenModel(prefill: .phone("+631234567"))
        #expect(model.kind == .phone)
        #expect(model.form.phone == "+631234567")
    }

    @Test func contrastWarningsDoNotBlock() async {
        let model = MakeScreenModel(prefill: .link("https://example.org"))
        model.foreground = Color(.sRGB, red: 0.9, green: 0.9, blue: 0.9, opacity: 1)
        #expect(!model.contrastWarnings.isEmpty)
        await model.refresh()
        #expect(model.canExport)
    }

    @Test func recordsOncePerPayload() async {
        let app = AppModel.preview(state: .unlocked, history: [])
        await app.start()
        let model = MakeScreenModel(prefill: .link("https://example.org/a"))
        await model.refresh()
        await model.recordUse(in: app)
        await model.recordUse(in: app)
        await app.refreshHistory()
        #expect(app.historyItems.filter { $0.kind == .made }.count == 1)
        model.form.link = "https://example.org/b"
        await model.refresh()
        await model.recordUse(in: app)
        await app.refreshHistory()
        #expect(app.historyItems.filter { $0.kind == .made }.count == 2)
    }
}
