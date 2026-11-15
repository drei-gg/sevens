import SwiftUI
import QRCore

/// Picks the view for a payload (SPEC §3).
struct ResultPayloadView: View {
    let payload: Payload
    let wifiPlan: WiFiJoinPlan

    var body: some View {
        switch payload {
        case .link(let info): ResultLinkView(link: info)
        case .appLink(let info): ResultAppLinkView(info: info)
        case .payment(let info): ResultPaymentView(info: info)
        case .crypto(let info): ResultCryptoView(info: info)
        case .wifi(let info): ResultWiFiView(info: info, plan: wifiPlan)
        case .contact(let info): ResultContactView(info: info)
        case .event(let info): ResultEventView(info: info)
        case .phone(let info): ResultPhoneView(info: info)
        case .sms(let info): ResultSMSView(info: info)
        case .email(let info): ResultEmailView(info: info)
        case .geo(let info): ResultGeoView(info: info)
        case .text(let info): ResultTextView(info: info)
        case .binary(let count): ResultBinaryView(byteCount: Int(count))
        }
    }
}
