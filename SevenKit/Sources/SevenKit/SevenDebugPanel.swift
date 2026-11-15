#if DEBUG
import SwiftUI
import SevenKitCore

/// Debug builds only. A `Section` for the Settings `Form`: pick a preset and the whole app
/// behaves as if the trial were there. Wrap its use in `#if DEBUG`.
public struct SevenDebugPanel: View {
    @Environment(Store.self) private var store
    public init() {}

    public var body: some View {
        Section {
            LabeledContent("Source") { Text(store.isUsingLocalSource ? "Local (debug)" : "StoreKit") }
            LabeledContent("State") { Text(String(describing: store.state)).textRole(.caption) }
            ForEach(LocalSource.Preset.allCases) { preset in
                Button(Self.title(preset)) { store.applyDebugPreset(preset) }
                    .accessibilityLabel(Self.title(preset))
            }
        } header: {
            Text("Debug: entitlement").foregroundStyle(Tokens.muted)
        } footer: {
            Text("Choosing a preset switches to the local source for this run.").foregroundStyle(Tokens.muted)
        }
    }

    static func title(_ preset: LocalSource.Preset) -> String {
        switch preset {
        case .notStarted: return "Not started"
        case .trialDay1: return "Trial day 1"
        case .trialDay7: return "Trial day 7"
        case .expired: return "Expired"
        case .unlocked: return "Unlocked"
        case .pending: return "Pending approval"
        }
    }
}

#Preview("Debug panel, light") {
    Form { SevenDebugPanel() }.sevenStore(SevenPreviewSupport.store(.fresh), unlock: .preview)
}
#Preview("Debug panel, dark") {
    Form { SevenDebugPanel() }.sevenStore(SevenPreviewSupport.store(.trial), unlock: .preview)
        .preferredColorScheme(.dark)
}
#Preview("Debug panel, largest text") {
    Form { SevenDebugPanel() }.sevenStore(SevenPreviewSupport.store(.fresh), unlock: .preview)
        .environment(\.dynamicTypeSize, .accessibility5)
}
#endif
