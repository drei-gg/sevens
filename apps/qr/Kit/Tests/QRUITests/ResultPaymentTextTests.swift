import Foundation
import Testing
import QRCore
@testable import QRUI

/// SPEC §5: each format's fields are first-class rows with human labels, every key the core
/// writes to `extra` has a human label, and nothing ever shows a raw dotted or camel-cased key.
@Suite struct ResultPaymentTextTests {
    /// Every key apps/qr/core/src/payments.rs writes to `PaymentInfo.extra`.
    static let coreKeys = ["mcc", "currencyNumeric", "networkID", "alternateName", "alternateCity", "alternateLanguage",
                           "bic", "purpose", "information", "creditorAddress", "debtorName", "referenceType", "billingInfo",
                           "kind", "mode", "url"] + (1...11).map { String(format: "additional.%02d", $0) }

    static func looksRaw(_ label: String) -> Bool {
        label.contains(".") || label.contains("_") || label.range(of: "[a-z][A-Z]", options: .regularExpression) != nil
            || label.first?.isLowercase == true
    }

    @Test func everyCoreKeyHasAHumanLabel() {
        for key in Self.coreKeys {
            let label = ResultPaymentText.label(forExtra: key)
            #expect(label != key, "\(key) shows as itself")
            #expect(!Self.looksRaw(label), "\(key) → \(label)")
        }
    }

    @Test func unknownKeysStillReadAsWords() {
        #expect(ResultPaymentText.label(forExtra: "additional.62") == "Additional data 62")
        #expect(!Self.looksRaw(ResultPaymentText.label(forExtra: "additional.62")))
        #expect(ResultPaymentText.readable("someNewKey") == "Some new key")
        #expect(ResultPaymentText.readable("merchant_city.alt") == "Merchant city alt")
        #expect(ResultPaymentText.readable("vatID") == "Vat ID")
        #expect(!Self.looksRaw(ResultPaymentText.readable("")))
    }

    @Test func fallbackLineIsTheSpecsWords() {
        #expect(ResultPaymentText.checkMatch == "Check this matches where you are.")
    }

    @Test func emvRowsAreFirstClass() {
        let info = PaymentInfo(format: .emv, payeeName: "Kopi Corner", payeeAccount: "0917", city: "Manila", country: "PH",
                               amount: "120.00", currency: "PHP", network: "InstaPay", isDynamic: false,
                               reference: "INV-7", note: "Lunch", party: .unknown, checksum: .valid,
                               extra: ["mcc": "5812", "additional.07": "T1"])
        let labels = ResultPaymentText.rows(for: info).map(\.label)
        #expect(labels == ["City", "Country", "Network", "Account", "Reference", "Note"])
        let more = ResultPaymentText.moreDetails(for: info)
        #expect(more.map(\.label).contains("Merchant category code"))
        #expect(more.map(\.label).contains("Terminal"))
        #expect(!more.contains { Self.looksRaw($0.label) })
    }

    @Test func ibanAndPayeeAddressAreNamed() {
        let epc = PaymentInfo(format: .epc, payeeName: "Red Cross", payeeAccount: "BE72000000001616", amount: "10", currency: "EUR",
                              reference: "Donation", party: .unknown, checksum: .valid, extra: ["bic": "BPOTBEB1"])
        #expect(ResultPaymentText.rows(for: epc).first { $0.value == "BE72000000001616" }?.label == "IBAN")
        let upi = PaymentInfo(format: .upi, payeeName: "Asha", payeeAccount: "asha@okbank", note: "Tea",
                              party: .unknown, checksum: .absent, extra: ["kind": "pay"])
        #expect(ResultPaymentText.rows(for: upi).first { $0.value == "asha@okbank" }?.label == "Payee address")
        #expect(ResultPaymentText.rows(for: upi).contains { $0.label == "Note" && $0.value == "Tea" })
        #expect(ResultPaymentText.moreDetails(for: upi).contains { $0.label == "Request" && $0.value == "One payment" })
    }

    @Test func swissRowsAndReferenceType() {
        let info = PaymentInfo(format: .swiss, payeeName: "Robert Schneider AG", payeeAccount: "CH4431999123000889012",
                               city: "Biel", country: "CH", amount: "1949.75", currency: "CHF", reference: "210000000003139471430009017",
                               party: .unknown, checksum: .valid, extra: ["referenceType": "QRR", "debtorName": "Pia Rutschmann"])
        #expect(ResultPaymentText.rows(for: info).map(\.label) == ["City", "Country", "IBAN", "Reference"])
        let more = ResultPaymentText.moreDetails(for: info)
        #expect(more.contains { $0.label == "Reference type" && $0.value == "QR reference" })
        #expect(more.contains { $0.label == "Payer" && $0.value == "Pia Rutschmann" })
    }

    @Test func alternateNameIsNotRepeatedInMoreDetails() {
        let info = PaymentInfo(format: .emv, party: .unknown, checksum: .absent, extra: ["alternateName": "コピー"])
        #expect(!ResultPaymentText.moreDetails(for: info).contains { $0.value == "コピー" })
    }

    @Test func copyDetailsUseHumanLabels() {
        let info = PaymentInfo(format: .emv, payeeName: "Kopi", party: .unknown, checksum: .valid,
                               extra: ["networkID": "com.example.pay", "additional.03": "Store 4"])
        let text = ResultPayloadText.paymentDetails(info)
        #expect(!text.contains("networkID"))
        #expect(!text.contains("additional."))
        #expect(text.contains("Store: Store 4"))
    }
}
