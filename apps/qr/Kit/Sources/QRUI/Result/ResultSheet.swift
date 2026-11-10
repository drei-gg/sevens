import SwiftUI
import QRCore
import SevenKit

public struct ResultSheet: View {
    let analysis: Analysis
    @Environment(\.dismiss) private var dismiss

    /// The result sheet's content for one analysis. The caller presents it (as a sheet from
    /// the scan screen, or pushed from history). It reads `AppModel` from the environment; it
    /// never records to History itself (the caller does).
    public init(analysis: Analysis) {
        self.analysis = analysis
    }

    public var body: some View {
        NavigationStack {
            ResultContent(analysis: analysis)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(String(localized: "result.done", defaultValue: "Done", bundle: .module)) { dismiss() }
                            .accessibilityIdentifier(QRAccessibilityID.resultDone)
                    }
                }
                .navigationDestination(for: ResultRoute.self) { route in
                    switch route {
                    case .embedded(let nested):
                        ResultContent(analysis: nested)
                    case .make(let request):
                        MakeScreen(prefill: request)
                            .gated(feature: String(localized: "result.feature.make", defaultValue: "Make", bundle: .module))
                    }
                }
        }
        .tint(Tokens.ink)
    }
}
