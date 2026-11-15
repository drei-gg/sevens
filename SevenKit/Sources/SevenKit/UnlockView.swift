import SwiftUI
import SevenKitCore

/// The paid-feature sheet: what the unlock adds, the price from StoreKit, Start trial (only if
/// it hasn't started), Unlock, Restore, and a plain "Not now". No countdown, no pre-selected
/// option. Needs `.sevenStore(_:unlock:)` above it.
public struct UnlockView: View {
    @Environment(Store.self) private var store
    @Environment(\.sevenUnlockContent) private var content
    @Environment(\.dismiss) private var dismiss

    private let feature: String?

    /// - Parameter feature: the name of what the person just tapped ("Peek"), if any.
    public init(feature: String? = nil) {
        self.feature = feature
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.l) {
                Text(content.title ?? seven("seven.unlock.title", "What the unlock adds"))
                    .textRole(.title).foregroundStyle(.primary)
                    .accessibilityAddTraits(.isHeader)
                if let feature {
                    Text(seven("seven.unlock.feature", "%@ is part of the unlock.", feature))
                        .textRole(.body).foregroundStyle(Tokens.muted)
                }
                SevenListLines(lines: content.features)
                statusNote
                actions
            }
            .sevenSheetPadding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .sevenReadableSheet()
        }
        .sevenScreenBackground()
        .onChange(of: store.paidFeaturesAvailable) { _, available in
            if available { dismiss() }
        }
    }

    @ViewBuilder private var statusNote: some View {
        switch store.state {
        case .expired(let end):
            note(seven("seven.unlock.expired", "Your free 7 days ended on %@.",
                       end.formatted(date: .abbreviated, time: .omitted)))
        case .pending:
            note(seven("seven.unlock.pending", "Waiting for approval. You’ll get access as soon as it comes through."))
        default:
            EmptyView()
        }
        if store.unlockDisplayPrice == nil && !store.isUsingLocalSource {
            note(seven("seven.unlock.priceUnavailable", "The price can’t be loaded right now. Check your connection and try again."))
        }
        if let error = store.lastError { note(error) }
    }

    private func note(_ text: String) -> some View {
        Text(text).textRole(.secondary)
            .accessibilityElement(children: .combine)
    }

    private var actions: some View {
        VStack(spacing: Tokens.Space.s) {
            if store.state == .notStarted && store.trialPolicy == .onPurchase {
                SevenButton(title: seven("seven.trial.start", "Start my free 7 days"), prominent: true, disabled: store.isBusy) {
                    Task { await store.startTrial() }
                }
            }
            SevenButton(title: store.unlockButtonTitle, brand: true, disabled: !store.canPurchase) {
                Task { await store.unlock() }
            }
            if store.isBusy { ProgressView().padding(.vertical, Tokens.Space.xs)
                .accessibilityLabel(seven("seven.busy", "Working")) }
            SevenPlainButton(title: seven("seven.restore", "Restore purchases"), disabled: store.isBusy) {
                Task { await store.restore() }
            }
            SevenButton(title: seven("seven.notNow", "Not now")) { dismiss() }  // same size and shape as the buy button, which alone is filled (SPEC §13)
        }
    }
}

#Preview("Unlock, light") {
    UnlockView(feature: "Peek")
        .sevenStore(SevenPreviewSupport.store(.fresh), unlock: .preview)
}
#Preview("Unlock, dark") {
    UnlockView(feature: "Peek")
        .sevenStore(SevenPreviewSupport.store(.expired), unlock: .preview)
        .preferredColorScheme(.dark)
}
#Preview("Unlock, largest text, no price") {
    UnlockView()
        .sevenStore(SevenPreviewSupport.store(.fresh, prices: false), unlock: .preview)
        .environment(\.dynamicTypeSize, .accessibility5)
}

extension UnlockContent {
    static let preview = UnlockContent(
        appName: "QR Code Kit",
        features: ["Peek: look at where a link goes", "Make: build QR codes", "History"]
    )
}
