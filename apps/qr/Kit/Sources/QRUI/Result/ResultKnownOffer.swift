import SwiftUI
import QRCore
import SevenKit

/// "I know this address" (SPEC §3): one question, two buttons. Keep warning me is the default,
/// the prominent one and says "Recommended"; the other stores the domain.
struct ResultKnownOffer: View {
    let question: String
    let domain: String
    let onKeep: () -> Void
    let onKnow: () -> Void

    private var recommended: String {
        String(localized: "result.known.recommended", defaultValue: "Recommended", bundle: .module)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.m) {
            Text(question)
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: Tokens.Space.s) {
                Button(action: onKeep) {
                    VStack(spacing: 2) {
                        Text(String(localized: "result.known.keep", defaultValue: "Keep warning me", bundle: .module))
                            .fontWeight(.semibold)
                        Text(recommended).font(.caption)
                    }
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .accessibilityLabel(Text(String(localized: "result.known.keep.label",
                                                defaultValue: "Keep warning me, recommended", bundle: .module)))
                .accessibilityIdentifier(QRAccessibilityID.knownKeep)

                Button(action: onKnow) {
                    Text(String(localized: "result.known.know", defaultValue: "I know this address", bundle: .module))
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                }
                .buttonStyle(.bordered)
                .accessibilityHint(Text(String(localized: "result.known.know.hint",
                                               defaultValue: "Remembers \(domain) on this phone. Other checks still run.",
                                               bundle: .module)))
                .accessibilityIdentifier(QRAccessibilityID.knownKnow)
            }
            .controlSize(.large)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Tokens.Space.m)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: Tokens.Radius.card))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(QRAccessibilityID.knownOffer)
    }
}
