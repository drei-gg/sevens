import SwiftUI
import QRCore
import SevenKit

public struct ResultSheet: View {
    @State private var analysis: Analysis
    @State private var generation = 0
    @State private var dismissedOffer = false
    @Environment(\.dismiss) private var dismiss
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
            ResultContent(analysis: analysis)
                .id(generation)
                .safeAreaInset(edge: .top, spacing: 0) {
                    if let domain = offer { offerCard(domain) }
                }
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

    /// Capped so the question never pushes the result off the screen at the largest text sizes.
    private func offerCard(_ domain: String) -> some View {
        let card = ResultKnownOffer(
            question: ResultKnownText.question(for: analysis, domain: domain), domain: domain,
            onKeep: { withAnimation { dismissedOffer = true } },
            onKnow: { know(domain) })
            .padding(Tokens.Space.m)
        return ViewThatFits(in: .vertical) {
            card
            ScrollView { card }.frame(maxHeight: 320)
        }
        .background(Tokens.paper)
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
