import SwiftUI
import QRCore

// Phase 3 seam (Opus): WP 3.2 owns this file and replaces the body. Keep the type name and init.

public struct ResultSheet: View {
    let analysis: Analysis

    /// The result sheet's content for one analysis. The caller presents it (as a sheet from
    /// the scan screen, or pushed from history). It reads `AppModel` from the environment; it
    /// never records to History itself (the caller does).
    public init(analysis: Analysis) {
        self.analysis = analysis
    }

    public var body: some View {
        Text(verbatim: "ResultSheet")
    }
}
