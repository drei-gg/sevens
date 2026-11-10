import SwiftUI
import QRCore

// Phase 3 seam (Opus): WP 3.3 owns this file and replaces the body. Keep the type name and init.

public struct MakeScreen: View {
    let prefill: MakeRequest?

    /// The Make screen; `prefill` comes from a result's "Make code" action. Callers wrap it in
    /// `.gated(feature:)` (paid). Pushed inside a NavigationStack.
    public init(prefill: MakeRequest? = nil) {
        self.prefill = prefill
    }

    public var body: some View {
        Text(verbatim: "MakeScreen")
    }
}
