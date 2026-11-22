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
    @Environment(\.dynamicTypeSize) private var typeSize

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

    private var actions: [ResultAction] {
        ResultActionPlan.actions(for: analysis.payload, capabilities: center.capabilities,
                                 hasText: analysis.code.text != nil, canPeek: analysis.canPeek)
    }

    /// Nothing is pinned at accessibility sizes: every action stacks at the end instead.
    private var pinsActions: Bool { !typeSize.isAccessibilitySize }

    /// The "I know this address" question, unless Peek found something of its own (SPEC §3).
    private var shownOffer: AnyView? {
        ResultActionPlan.peekBlocksKnownOffer(center.peekReport, offline: analysis.level) ? nil : afterSignals
    }

    var body: some View {
        let level = center.level
        let report = center.peekReport
        let layout = ResultActionPlan.layout(actions, level: level)
        let offer = shownOffer
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.l) {
                VerdictHeader(level: level, typeName: VerdictText.typeName(for: analysis.payload.typeID),
                              signals: analysis.allSignals + (report?.signals ?? []))
                ResultSignalList(rows: ResultReasons.rows(for: analysis, peek: report))
                if report != nil { peekFound }
                if let offer { offer }
                ResultPayloadView(payload: analysis.payload, wifiPlan: wifiPlan)
                if pinsActions {
                    ResultActionChips(actions: layout.chips, analysis: analysis, center: center)
                }
                ResultEmbeddedList(embedded: analysis.embedded)
                if let notice = center.notice {
                    Text(notice).textRole(.body)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                if !pinsActions {
                    ResultActionBar(actions: [layout.primary, layout.secondary].compactMap { $0 } + layout.chips,
                                    analysis: analysis, center: center)
                }
            }
            .padding(Tokens.Space.m)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if pinsActions {
                ResultPinnedActions(layout: layout, analysis: analysis, center: center, quiet: offer != nil)
            }
        }
        .sevenScreenBackground()
        .navigationTitle(String(localized: "result.title", defaultValue: "Result", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            String(localized: "result.confirm.title", defaultValue: "Open this link?", bundle: .module),
            isPresented: Binding(get: { center.pendingOpen != nil }, set: { if !$0 { center.pendingOpen = nil } }),
            titleVisibility: .visible, presenting: center.pendingOpen
        ) { request in
            Button(String(localized: "result.confirm.open", defaultValue: "Open \(request.domain) anyway", bundle: .module),
                   role: .destructive) { center.perform(request, openURL: openURL) }
            Button(String(localized: "result.confirm.cancel", defaultValue: "Don’t open", bundle: .module), role: .cancel) {}
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

    /// After a Peek: its reasons are in the list above; this shows the whole Peek again.
    private var peekFound: some View {
        Button {
            center.startPeek()
        } label: {
            Label(String(localized: "result.peek.found", defaultValue: "See what Peek found", bundle: .module,
                         comment: "Button under the reasons after a Peek; shows the Peek sheet again."),
                  systemImage: "eye")
                .textRole(.rowTitle)
        }
        .buttonStyle(.borderless)
        .accessibilityIdentifier(QRAccessibilityID.resultPeekFound)
    }
}
