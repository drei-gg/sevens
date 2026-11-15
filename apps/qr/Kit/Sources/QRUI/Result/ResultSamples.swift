import Foundation
import QRCore

/// Hand-built analyses for previews and tests (no `BundledData` needed).
enum ResultSamples {
    static func link(_ text: String, host: String, domain: String?, level: Level = .normal, signals: [Signal] = [],
                     normalized: String? = nil, scheme: String = "https") -> Analysis {
        let info = LinkInfo(original: text, scheme: scheme, hostASCII: host, hostUnicode: host, registrableDomain: domain,
                            pathAndQuery: "/", normalized: normalized ?? text)
        return Analysis(code: ScannedCode(text: text), payload: .link(info), signals: signals, embedded: [], level: level)
    }

    static var normalLink: Analysis { link("https://example.org/menu", host: "example.org", domain: "example.org") }

    static var scamLink: Analysis {
        link("http://login.paypa1-verify.example/account?id=1", host: "login.paypa1-verify.example", domain: "paypa1-verify.example",
             level: .likelyScam, signals: [Signal(.lookalikeBrand, ["brand": "PayPal"]), Signal(.plainHttp)], scheme: "http")
    }

    static var payment: Analysis {
        let info = PaymentInfo(format: .emv, payeeName: "Kopi Corner", city: "Manila", country: "PH", amount: "120.00", currency: "PHP",
                               network: "InstaPay", isDynamic: false, party: .unknown, checksum: .valid, extra: ["alternateName": "コピーコーナー"])
        return Analysis(code: ScannedCode(text: "000201…"), payload: .payment(info), signals: [], embedded: [], level: .normal)
    }

    static var crypto: Analysis {
        let info = CryptoInfo(scheme: "bitcoin", network: "Bitcoin", address: "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", amount: "0.01")
        return Analysis(code: ScannedCode(text: "bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq?amount=0.01"), payload: .crypto(info),
                        signals: [], embedded: [], level: .normal)
    }

    static var wifi: Analysis {
        let info = WiFiInfo(ssid: "Cafe Guest", security: "WPA", password: "hunter2hunter2")
        return Analysis(code: ScannedCode(text: "WIFI:T:WPA;S:Cafe Guest;P:hunter2hunter2;;"), payload: .wifi(info),
                        signals: [], embedded: [], level: .normal)
    }

    static var contact: Analysis {
        let info = ContactInfo(name: "Ana Reyes", organization: "Reyes Bakery", phones: ["+63 917 555 0100"],
                               emails: ["ana@example.org"], urls: ["https://example.org"], links: ["https://example.org"])
        return Analysis(code: ScannedCode(text: "BEGIN:VCARD"), payload: .contact(info), signals: [],
                        embedded: [normalLink], level: .normal)
    }

    static var event: Analysis {
        let info = EventInfo(summary: "Launch party", start: Date(timeIntervalSince1970: 1_800_000_000),
                             end: Date(timeIntervalSince1970: 1_800_007_200), location: "Pier 7")
        return Analysis(code: ScannedCode(text: "BEGIN:VEVENT"), payload: .event(info), signals: [], embedded: [], level: .normal)
    }

    static var text: Analysis {
        Analysis(code: ScannedCode(text: "Hello there"), payload: .text(TextInfo(text: "Hello there")), signals: [], embedded: [], level: .normal)
    }
}
