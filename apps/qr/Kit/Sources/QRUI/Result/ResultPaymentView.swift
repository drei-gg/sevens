import SwiftUI
import QRCore
import SevenKit

/// Payment codes (SPEC §5): who gets paid, in large type, then the amount, and the rest.
struct ResultPaymentView: View {
    let info: PaymentInfo

    /// Large type: the payee's name, else the account, else a plain statement.
    var payee: String {
        if let name = info.payeeName, !name.isEmpty { return name }
        if let account = info.payeeAccount, !account.isEmpty { return account }
        return String(localized: "result.payment.noPayee", defaultValue: "No name in this code", bundle: .module)
    }

    /// "Person" / "Business" only when the network's published spec says so (D11).
    var partyText: String? {
        switch info.party {
        case .person: return String(localized: "result.payment.person", defaultValue: "Person", bundle: .module)
        case .business: return String(localized: "result.payment.business", defaultValue: "Business", bundle: .module)
        case .unknown: return nil
        }
    }

    var amountText: String {
        guard let amount = info.amount, !amount.isEmpty else {
            return String(localized: "result.payment.noAmount", defaultValue: "No amount in the code; you enter it", bundle: .module)
        }
        return [info.currency, amount].compactMap { $0 }.joined(separator: " ")
    }

    var kindText: String? {
        guard let isDynamic = info.isDynamic else { return nil }
        return isDynamic
            ? String(localized: "result.payment.dynamic", defaultValue: "Dynamic code, made for one payment", bundle: .module)
            : String(localized: "result.payment.static", defaultValue: "Static code, the same every time", bundle: .module)
    }

    var checksumText: String? {
        switch info.checksum {
        case .valid:
            return String(localized: "result.payment.checksumValid", defaultValue: "Checksum matches", bundle: .module)
        case .invalid:
            return String(localized: "result.payment.checksumInvalid", defaultValue: "Checksum doesn’t match. The code may be damaged or altered.", bundle: .module)
        case .absent:
            return info.format == .emv
                ? String(localized: "result.payment.checksumAbsent", defaultValue: "This code has no checksum", bundle: .module)
                : nil
        }
    }

    var body: some View {
        ResultCard(title: String(localized: "result.payment.title", defaultValue: "Who you would pay", bundle: .module)) {
            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                Text(payee)
                    .textRole(.title).foregroundStyle(.primary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                if let alternate = info.extra["alternateName"], !alternate.isEmpty {
                    Text(alternate).textRole(.body).foregroundStyle(Tokens.muted)
                        .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                }
            }
            if let partyText {
                Text(partyText).textRole(.rowTitle, emphasized: true).foregroundStyle(.primary)
            } else {
                Text(ResultPaymentText.checkMatch)
                    .textRole(.rowTitle, emphasized: true).foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ResultField(label: String(localized: "result.payment.amount", defaultValue: "Amount", bundle: .module), value: amountText)
            ForEach(ResultPaymentText.rows(for: info), id: \.self) { row in
                ResultField(label: row.label, value: row.value, monospaced: row.label == ResultPaymentText.accountLabel(info.format))
            }
            if let kindText { Text(kindText).textRole(.caption) }
            if let checksumText {
                Label {
                    Text(checksumText).foregroundStyle(.primary)
                } icon: {
                    Image(systemName: info.checksum == .invalid ? "xmark.octagon" : "checkmark.circle")
                        .foregroundStyle(info.checksum == .invalid ? Tokens.danger : Tokens.muted)
                }
                .textRole(.caption)
            }
            let rows = ResultPaymentText.moreDetails(for: info)
            if !rows.isEmpty {
                DisclosureGroup(String(localized: "result.payment.more", defaultValue: "More details", bundle: .module)) {
                    VStack(alignment: .leading, spacing: Tokens.Space.s) {
                        ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                            ResultField(label: row.label, value: row.value)
                        }
                    }
                    .padding(.top, Tokens.Space.s)
                }
                .foregroundStyle(.primary)
            }
        }
    }
}
