import SwiftUI
import QRCore
import SevenKit

public struct ResultSheet: View {
    @State private var analysis: Analysis
    @State private var generation = 0
    @State private var dismissedOffer = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(AppModel.self) private var model: AppModel?

    /// The result sheet's content for one analysis. The caller presents it (as a sheet from
    /// the scan screen, or pushed from history). It reads `AppModel` from the environment; it
    /// never records to History itself (the caller does).
    public init(analysis: Analysis) {
        _analysis = State(initialValue: analysis)
    }

    /// The domain to ask about, or nil (SPEC §3): only for list-based warnings, once.
    private var offer: String? {
        guard !dismissedOffer, let model else { return nil }
        return model.knownAddresses.offer(for: analysis)
    }

    public var body: some View {
        NavigationStack {
            ResultContent(analysis: analysis, afterSignals: offer.map { AnyView(offerCard($0)) })
                .id(generation)
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
    }

    /// Inside the result's scroll view, after the reasons: the verdict stays at the top (SPEC §3).
    private func offerCard(_ domain: String) -> some View {
        ResultKnownOffer(
            question: ResultKnownText.question(for: analysis, domain: domain), domain: domain,
            onKeep: { withAnimation(reduceMotion ? nil : .smooth) { dismissedOffer = true } },
            onKnow: { know(domain) })
    }

    private func know(_ domain: String) {
        guard let model else { return }
        let code = analysis.code
        Task {
            guard await model.knowAddress(domain) else { return }
            analysis = await model.analysis(of: code)
            generation += 1
        }
    }
}
