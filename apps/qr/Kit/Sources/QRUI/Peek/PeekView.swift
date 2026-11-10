import SwiftUI
import QRCore

// Phase 3 seam (Opus): WP 3.2 owns this file and replaces the body. Keep the type name and init.

public struct PeekView: View {
    let model: PeekModel

    public init(model: PeekModel) {
        self.model = model
    }

    public var body: some View {
        Text(verbatim: "PeekView")
    }
}
