import SwiftUI
import QRCore
import SevenKit

/// Phone, SMS, email and location results. Small cards; the actions live in the action row.

struct ResultPhoneView: View {
    let info: PhoneInfo

    var body: some View {
        ResultCard {
            Text(info.number)
                .font(.largeTitle.weight(.bold)).foregroundStyle(Tokens.ink)
                .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
        }
    }
}

struct ResultSMSView: View {
    let info: SMSInfo

    var body: some View {
        ResultCard {
            ResultField(label: String(localized: "result.sms.to", defaultValue: "To", bundle: .module), value: info.number)
            if let body = info.body, !body.isEmpty {
                ResultField(label: String(localized: "result.sms.body", defaultValue: "Message", bundle: .module), value: body)
            }
        }
    }
}

struct ResultEmailView: View {
    let info: EmailInfo

    var body: some View {
        ResultCard {
            ResultField(label: String(localized: "result.email.to", defaultValue: "To", bundle: .module), value: info.address)
            if let subject = info.subject, !subject.isEmpty {
                ResultField(label: String(localized: "result.email.subject", defaultValue: "Subject", bundle: .module), value: subject)
            }
            if let body = info.body, !body.isEmpty {
                ResultField(label: String(localized: "result.email.body", defaultValue: "Message", bundle: .module), value: body)
            }
        }
    }
}

struct ResultGeoView: View {
    let info: GeoInfo

    var body: some View {
        ResultCard {
            ResultField(label: String(localized: "result.geo.coordinates", defaultValue: "Coordinates", bundle: .module),
                        value: info.latitude + ", " + info.longitude, monospaced: true)
            if let altitude = info.altitude, !altitude.isEmpty {
                ResultField(label: String(localized: "result.geo.altitude", defaultValue: "Altitude", bundle: .module), value: altitude)
            }
            if let query = info.query, !query.isEmpty {
                ResultField(label: String(localized: "result.geo.label", defaultValue: "Label", bundle: .module), value: query)
            }
        }
    }
}
