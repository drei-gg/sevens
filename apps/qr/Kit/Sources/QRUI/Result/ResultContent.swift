import SwiftUI
import QRCore
import SevenKit

/// One analysis laid out: verdict, reasons, payload, links inside, actions. Used for the sheet's
/// root and for each pushed embedded link.
struct ResultContent: View {
    let analysis: Analysis
    /// Shown right after the signals (the "I know this address" question, SPEC §3), so the
    /// verdict always stays at the top.
    let afterSignals: AnyView?
    @State private var center: ResultActionCenter
    @Environment(\.openURL) private var openURL

    init(analysis: Analysis, capabilities: ResultCapabilities = .current(), afterSignals: AnyView? = nil) {
        self.analysis = analysis
        self.afterSignals = afterSignals
        _center = State(initialValue: ResultActionCenter(analysis: analysis, capabilities: capabilities))
    }

    private var wifiPlan: WiFiJoinPlan {
        if case .wifi(let info) = analysis.payload {
            return ResultActionPlan.wifiJoinPlan(info, capabilities: center.capabilities)
        }
        return .join
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.l) {
                VerdictHeader(level: analysis.level, typeName: VerdictText.typeName(for: analysis.payload.typeID),
                              signals: analysis.allSignals)
                ResultSignalList(signals: analysis.signals)
                if let afterSignals { afterSignals }
                ResultPayloadView(payload: analysis.payload, wifiPlan: wifiPlan)
                ResultEmbeddedList(embedded: analysis.embedded)
                if let notice = center.notice {
                    Text(notice).font(.subheadline).foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                ResultActionBar(actions: ResultActionPlan.actions(for: analysis.payload, capabilities: center.capabilities,
                                                                  hasText: analysis.code.text != nil),
                                analysis: analysis, center: center)
            }
            .padding(Tokens.Space.m)
        }
        .navigationTitle(String(localized: "result.title", defaultValue: "Result", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            String(localized: "result.confirm.title", defaultValue: "Open this link?", bundle: .module),
            isPresented: Binding(get: { center.pendingOpen != nil }, set: { if !$0 { center.pendingOpen = nil } }),
            titleVisibility: .visible, presenting: center.pendingOpen
        ) { request in
            Button(String(localized: "result.confirm.open", defaultValue: "Open \(request.domain) anyway", bundle: .module),
                   role: .destructive) { center.perform(request, openURL: openURL) }
            Button(String(localized: "result.confirm.cancel", defaultValue: "Don't open", bundle: .module), role: .cancel) {}
        } message: { request in
            Text(String(localized: "result.confirm.message",
                        defaultValue: "This looks like a scam. It would take you to \(request.domain).", bundle: .module))
        }
        .fullScreenCover(item: Binding(get: { center.safari }, set: { center.safari = $0 })) { item in
            ResultSafariView(url: item.url) { center.safari = nil }.ignoresSafeArea()
        }
        .sheet(item: Binding(get: { center.sheet }, set: { center.sheet = $0 })) { sheet in
            switch sheet {
            case .contact(let info): ResultContactEditor(info: info) { center.sheet = nil }.ignoresSafeArea()
            case .event(let info): ResultEventEditor(info: info) { center.sheet = nil }.ignoresSafeArea()
            case .message(let number, let body): ResultMessageComposer(number: number, body: body) { center.sheet = nil }.ignoresSafeArea()
            }
        }
        .sheet(isPresented: Binding(get: { center.peekModel != nil }, set: { if !$0 { center.endPeek() } })) {
            if let model = center.peekModel { PeekView(model: model) }
        }
    }
}
