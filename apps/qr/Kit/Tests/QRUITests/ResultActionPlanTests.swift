import Testing
import Foundation
import QRCore
@testable import QRUI

@Suite struct ResultActionPlanTests {
    let device = ResultCapabilities(wifiJoinAvailable: true, canAddEvent: true, canComposeMessage: false)
    let noJoin = ResultCapabilities(wifiJoinAvailable: false)

    @Test func linkOffersPeekOpenCopyShareMake() {
        let a = ResultSamples.normalLink
        #expect(ResultActionPlan.actions(for: a.payload, capabilities: device) == [.peek, .open, .copy, .share, .makeCode])
    }

    @Test func dangerousSchemeLinkCannotBeOpenedOrPeeked() {
        let info = LinkInfo(original: "javascript:alert(1)", scheme: "javascript", hostASCII: "", hostUnicode: "", pathAndQuery: "alert(1)", normalized: "javascript:alert(1)")
        let actions = ResultActionPlan.actions(for: .link(info), capabilities: device)
        #expect(!actions.contains(.open) && !actions.contains(.peek))
        #expect(ResultActionURLs.open(info) == nil)
    }

    @Test func paymentOpenInOnlyForURIShaped() {
        let upi = Payload.payment(PaymentInfo(format: .upi))
        let emv = Payload.payment(PaymentInfo(format: .emv))
        #expect(ResultActionPlan.actions(for: upi, capabilities: device).contains(.openPaymentIn))
        #expect(!ResultActionPlan.actions(for: emv, capabilities: device).contains(.openPaymentIn))
        #expect(ResultActionPlan.actions(for: emv, capabilities: device).contains(.copyDetails))
    }

    @Test func wifiPlans() {
        let wpa = WiFiInfo(ssid: "a", security: "WPA", password: "12345678")
        let open = WiFiInfo(ssid: "a", security: "nopass")
        let eap = WiFiInfo(ssid: "a", security: "WPA2-EAP", eapMethod: "PEAP")
        let none = WiFiInfo(ssid: "a", security: "WPA")
        #expect(ResultActionPlan.wifiJoinPlan(wpa, capabilities: device) == .join)
        #expect(ResultActionPlan.wifiJoinPlan(open, capabilities: device) == .join)
        #expect(ResultActionPlan.wifiJoinPlan(eap, capabilities: device) == .settingsOnly)
        #expect(ResultActionPlan.wifiJoinPlan(none, capabilities: device) == .cannotJoin)
        #expect(ResultActionPlan.wifiJoinPlan(wpa, capabilities: noJoin) == .copyAndSettings)
        #expect(ResultActionPlan.actions(for: .wifi(wpa), capabilities: noJoin).contains(.copyPassword))
        #expect(!ResultActionPlan.actions(for: .wifi(wpa), capabilities: noJoin).contains(.joinWiFi))
        #expect(!ResultActionPlan.actions(for: .wifi(eap), capabilities: device).contains(.joinWiFi))
    }

    @Test func eventActionHiddenWhenRestricted() {
        let p = Payload.event(EventInfo(summary: "x"))
        #expect(ResultActionPlan.actions(for: p, capabilities: ResultCapabilities(canAddEvent: false)).contains(.addEvent) == false)
        #expect(ResultActionPlan.actions(for: p, capabilities: device).contains(.addEvent))
    }

    @Test func openConfirmationOnlyForLikelyScam() {
        #expect(ResultActionPlan.needsOpenConfirmation(level: .likelyScam))
        #expect(!ResultActionPlan.needsOpenConfirmation(level: .careful))
        #expect(!ResultActionPlan.needsOpenConfirmation(level: .normal))
    }

    @Test func binaryHasNoActions() {
        #expect(ResultActionPlan.actions(for: .binary(byteCount: 3), capabilities: device, hasText: false).isEmpty)
    }

    @Test func makeMappingCoversPayloads() {
        #expect(ResultMakeMapping.request(for: ResultSamples.normalLink) == .link("https://example.org/menu"))
        #expect(ResultMakeMapping.request(for: ResultSamples.text) == .text("Hello there"))
        #expect(ResultMakeMapping.request(for: ResultSamples.payment) == .text("000201…"))
        if case .wifi = ResultMakeMapping.request(for: ResultSamples.wifi) {} else { Issue.record("wifi should map to .wifi") }
        let phone = Analysis(code: ScannedCode(text: "tel:123"), payload: .phone(PhoneInfo(number: "123")), signals: [], embedded: [], level: .normal)
        #expect(ResultMakeMapping.request(for: phone) == .phone("123"))
        let bin = Analysis(code: ScannedCode(text: nil, bytes: [1], symbology: .qr), payload: .binary(byteCount: 1), signals: [], embedded: [], level: .normal)
        #expect(ResultMakeMapping.request(for: bin) == nil)
    }

    @Test func urlsAreBuiltSafely() {
        #expect(ResultActionURLs.call("+63 (917) 555-0100")?.absoluteString == "tel:+639175550100")
        #expect(ResultActionURLs.call("abc") == nil)
        let mail = ResultActionURLs.mail(EmailInfo(address: "a@example.org", subject: "Hi there", body: "x&y"))?.absoluteString
        #expect(mail == "mailto:a@example.org?subject=Hi%20there&body=x%26y")
        #expect(ResultActionURLs.maps(GeoInfo(latitude: "14.5", longitude: "121.0"))?.absoluteString == "maps:?ll=14.5,121.0")
        #expect(ResultActionURLs.maps(GeoInfo(latitude: "nan", longitude: "1")) == nil)
        #expect(ResultActionURLs.sms(SMSInfo(number: "123", body: "a b"))?.absoluteString == "sms:123?body=a%20b")
    }

    @Test func linkDisplayPutsRegistrableDomainFirst() {
        let a = ResultSamples.scamLink
        guard case .link(let link) = a.payload else { Issue.record("not a link"); return }
        #expect(ResultLinkDisplay.headline(link) == "paypa1-verify.example")
        #expect(ResultLinkDisplay.fullHost(link) == "login.paypa1-verify.example")
        #expect(ResultLinkDisplay.openedAs(link) == nil)
    }
}
