import SwiftUI
import QRCore
import SevenKit

/// Crypto addresses: the whole address in groups of four, to compare against another copy.
struct ResultCryptoView: View {
    let info: CryptoInfo

    var body: some View {
        let groups = info.addressGroups
        ResultCard {
            Text(info.network)
                .font(.title.bold()).foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: 2) {
                Text(info.isLightningInvoice
                     ? String(localized: "result.crypto.invoice", defaultValue: "Invoice", bundle: .module)
                     : String(localized: "result.crypto.address", defaultValue: "Address", bundle: .module))
                    .font(.caption).foregroundStyle(Tokens.muted)
                Text(groups.joined(separator: " "))
                    .font(.body.monospaced())
                    .foregroundStyle(.primary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .speechSpellsOutCharacters()
                    .accessibilityLabel(groups.joined(separator: ", "))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let amount = info.amount, !amount.isEmpty {
                ResultField(label: String(localized: "result.crypto.amount", defaultValue: "Amount", bundle: .module), value: amount)
            }
            if let label = info.label, !label.isEmpty {
                ResultField(label: String(localized: "result.crypto.label", defaultValue: "Label", bundle: .module), value: label)
            }
            if let message = info.message, !message.isEmpty {
                ResultField(label: String(localized: "result.crypto.message", defaultValue: "Message", bundle: .module), value: message)
            }
            if let chain = info.chainID, !chain.isEmpty {
                ResultField(label: String(localized: "result.crypto.chain", defaultValue: "Chain", bundle: .module), value: chain, muted: true)
            }
        }
    }
}
