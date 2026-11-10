import SwiftUI
import QRCore

// Phase 3 seam (Opus): WP 3.1 owns this file and replaces the body. Keep the type name and init.

public struct FirstLaunchFlow: View {
    let onFinish: () -> Void

    /// Before you buy, shown once at first launch (PLAN §6.10, D1).
    public init(onFinish: @escaping () -> Void) {
        self.onFinish = onFinish
    }

    public var body: some View {
        Text(verbatim: "FirstLaunchFlow")
    }
}
