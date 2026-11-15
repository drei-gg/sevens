import SwiftUI
import SevenKitCore

// Gate: how paid entry points stay behind the unlock (SPEC §13).
//
// Setup, once, near the root:
//
//     ContentView().sevenStore(store, unlock: UnlockContent(appName: "…", features: ["…"]))
//
// Use:
//
//  - `GatedButton(feature: "Peek") { openPeek() } label: { Label("Peek", systemImage: "eye") }`
//    runs the action when `store.paidFeaturesAvailable`, otherwise presents `UnlockView` as a
//    sheet instead.
//  - `.gated(feature: "Make")` on a screen (a navigation destination): shows the content when
//    paid features are available, otherwise a short placeholder and the unlock sheet.
//  - `.unlockSheet(isPresented:feature:)` to present the sheet from your own button.
//  - `store.paidFeaturesAvailable` for a plain check (History saving, the Make extension's
//    snapshot reader `ExtensionEntitlement`).
//
// Nothing is presented at launch; the sheet only appears after the person taps something paid.

/// A button for a paid entry point.
public struct GatedButton<Label: View>: View {
    @Environment(Store.self) private var store
    @State private var showUnlock = false
    private let feature: String?
    private let action: () -> Void
    private let label: Label

    public init(feature: String? = nil, action: @escaping () -> Void, @ViewBuilder label: () -> Label) {
        self.feature = feature
        self.action = action
        self.label = label()
    }

    public var body: some View {
        Button {
            if store.paidFeaturesAvailable { action() } else { showUnlock = true }
        } label: {
            label
        }
        .unlockSheet(isPresented: $showUnlock, feature: feature)
    }
}

struct GateModifier: ViewModifier {
    @Environment(Store.self) private var store
    @State private var showUnlock = false
    @State private var offered = false
    let feature: String?

    func body(content: Content) -> some View {
        Group {
            if store.paidFeaturesAvailable {
                content
            } else {
                VStack(spacing: Tokens.Space.m) {
                    Text(feature.map { seven("seven.gate.needsUnlock", "%@ is part of the unlock.", $0) }
                         ?? seven("seven.gate.needsUnlockPlain", "This is part of the unlock."))
                        .textRole(.rowTitle, emphasized: true).foregroundStyle(.primary).multilineTextAlignment(.center)
                    SevenButton(title: seven("seven.gate.see", "See what the unlock adds"), prominent: true) { showUnlock = true }
                }
                .padding(Tokens.Space.l)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onAppear {
                    if !offered { offered = true; showUnlock = true }
                }
            }
        }
        .unlockSheet(isPresented: $showUnlock, feature: feature)
    }
}

extension View {
    /// Shows this view only while paid features are available; otherwise a placeholder, and the
    /// unlock sheet once.
    public func gated(feature: String? = nil) -> some View {
        modifier(GateModifier(feature: feature))
    }

    /// Presents `UnlockView` as a sheet.
    public func unlockSheet(isPresented: Binding<Bool>, feature: String? = nil) -> some View {
        sheet(isPresented: isPresented) {
            NavigationStack { UnlockView(feature: feature) }
        }
    }
}

#Preview("Gated button, locked") {
    GatedButton(feature: "Peek") {} label: { Label("Peek", systemImage: "eye") }
        .sevenStore(SevenPreviewSupport.store(.fresh), unlock: .preview)
}
#Preview("Gated screen, dark") {
    Text("Make").gated(feature: "Make")
        .sevenStore(SevenPreviewSupport.store(.expired), unlock: .preview)
        .preferredColorScheme(.dark)
}
#Preview("Gated screen, largest text") {
    Text("Make").gated(feature: "Make")
        .sevenStore(SevenPreviewSupport.store(.fresh), unlock: .preview)
        .environment(\.dynamicTypeSize, .accessibility5)
}
