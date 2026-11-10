import SwiftUI
import QRCore

// Phase 3 seam (Opus): WP 3.4 owns this file and replaces the body. Keep the type name and init.

public struct HistoryDetail: View {
    let item: HistoryItem

    public init(item: HistoryItem) {
        self.item = item
    }

    public var body: some View {
        Text(verbatim: "HistoryDetail")
    }
}
