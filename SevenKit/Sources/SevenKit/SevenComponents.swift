import SwiftUI
import SevenKitCore

/// Small shared pieces for the SevenKit screens.
struct SevenListLines: View {
    let lines: [String]
    var symbol = "checkmark.circle"

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.s) {
                    Image(systemName: symbol).foregroundStyle(Tokens.muted).accessibilityHidden(true)
                    Text(line).font(.body).foregroundStyle(Tokens.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// A full-width button; equal weight for every use (no pre-selected option).
struct SevenButton: View {
    let title: String
    var disabled = false
    /// Accessibility identifier for UI tests; empty means none.
    var identifier = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).font(.headline).multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 28)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .tint(Tokens.ink)
        .accessibilityIdentifier(identifier)
        .disabled(disabled)
        .accessibilityLabel(title)
    }
}

/// "Not now", Restore: plain text buttons.
struct SevenPlainButton: View {
    let title: String
    var disabled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).font(.body).frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.plain)
        .foregroundStyle(Tokens.muted)
        .disabled(disabled)
        .accessibilityLabel(title)
    }
}

extension Store {
    /// "Unlock for <price>" or, with no price, "Unlock".
    var unlockButtonTitle: String {
        guard let price = unlockDisplayPrice else { return seven("seven.unlock.buyNoPrice", "Unlock") }
        return seven("seven.unlock.buy", "Unlock for %@", price)
    }
}

enum SevenPreviewSupport {
    @MainActor
    static func store(_ facts: EntitlementFactsPreset, prices: Bool = true) -> Store {
        let config = SevenConfig(trialProductID: "preview.trial", unlockProductID: "preview.unlock")
        let source = FixedSource(facts: facts.facts, prices: prices ? SevenPrices(trial: "Free", unlock: "Test price") : nil)
        let store = Store(config: config, source: source, trialStartStore: MemoryTrialStartStore(),
                          defaults: UserDefaults(suiteName: "seven.preview") ?? .standard)
        Task { await store.start() }
        return store
    }
}

enum EntitlementFactsPreset {
    case fresh, trial, expired, unlocked, pending
    var facts: EntitlementFacts {
        let now = Date()
        switch self {
        case .fresh: return EntitlementFacts()
        case .trial: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-2 * 86_400))
        case .expired: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-9 * 86_400))
        case .unlocked: return EntitlementFacts(hasUnlock: true)
        case .pending: return EntitlementFacts(purchasePending: true)
        }
    }
}
