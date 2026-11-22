import SwiftUI
import QRCore
import SevenKit

/// The body of the Peek sheet, driven by plain values so previews don't need a network.
struct PeekViewContent: View {
    let original: Analysis
    let state: PeekModel.State
    let capture: PeekCapture?
    let ageDays: Int?

    private var report: PeekReport? {
        if case .finished(let report) = state { return report }
        return nil
    }

    private var level: Level { report?.level ?? original.level }

    private var allSignals: [Signal] { original.allSignals + (report?.signals ?? []) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.l) {
                VerdictHeader(level: level, typeName: VerdictText.typeName(for: original.payload.typeID),
                              signals: allSignals, identifier: "peek.verdict")
                progress
                if case .failed = state {
                    Text(String(localized: "peek.notLink", defaultValue: "This isn’t a web link, so there is nothing to Peek at.", bundle: .module))
                        .textRole(.body).foregroundStyle(.primary)
                }
                if let reason = PeekViewText.notFetched(capture) {
                    Text(reason).textRole(.body).foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("peek.notFetched")
                }
                if let report { ResultSignalList(signals: report.signals) }
                hops
                if let report, let capture { details(report: report, capture: capture) }
            }
            .padding(Tokens.Space.m)
        }
        .sevenScreenBackground()
    }

    @ViewBuilder private var progress: some View {
        switch state {
        case .running(let stage):
            HStack(spacing: Tokens.Space.s) {
                ProgressView()
                Text(stage == .fetching
                     ? String(localized: "peek.fetching", defaultValue: "Fetching…", bundle: .module)
                     : String(localized: "peek.checkingAge", defaultValue: "Checking the domain’s age…", bundle: .module))
                    .textRole(.body).foregroundStyle(.primary)
            }
            .accessibilityElement(children: .combine)
        case .idle:
            Text(String(localized: "peek.starting", defaultValue: "Starting…", bundle: .module))
                .textRole(.body).foregroundStyle(Tokens.muted)
        case .finished, .failed:
            EmptyView()
        }
    }

    @ViewBuilder private var hops: some View {
        let hopList = capture?.hops ?? []
        if !hopList.isEmpty {
            let rows = PeekHopRow.rows(hops: hopList, analyses: report?.hopAnalyses ?? [], data: .shared)
            ResultCard(title: String(localized: "peek.hops", defaultValue: "Where it goes", bundle: .module)) {
                VStack(alignment: .leading, spacing: Tokens.Space.m) {
                    ForEach(rows, id: \.number) { row in
                        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                            Text("\(row.number). \(row.domain)")
                                .textRole(.rowTitle, emphasized: true).foregroundStyle(.primary)
                                .fixedSize(horizontal: false, vertical: true)
                            Text(row.url).textRole(.caption)
                                .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                            Text(row.status.map { code in String(localized: "peek.status", defaultValue: "Answered \(code)", bundle: .module) }
                                 ?? String(localized: "peek.notFetched", defaultValue: "Not fetched", bundle: .module))
                                .textRole(.caption)
                            if let level = row.level, level != .normal { VerdictLabel(level: level) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
    }

    private func details(report: PeekReport, capture: PeekCapture) -> some View {
        ResultCard(title: String(localized: "peek.page", defaultValue: "The page", bundle: .module)) {
            ResultField(label: String(localized: "peek.title", defaultValue: "Title", bundle: .module),
                        value: report.title ?? String(localized: "peek.noTitle", defaultValue: "No title found", bundle: .module))
            ResultField(label: String(localized: "peek.connection", defaultValue: "Connection", bundle: .module),
                        value: PeekViewText.connection(capture))
            ResultField(label: String(localized: "peek.asks", defaultValue: "Asks for", bundle: .module),
                        value: PeekViewText.asks(report.signals)
                            ?? (PeekViewText.pageWasRead(capture)
                                ? String(localized: "peek.asksNothing", defaultValue: "No password, code or card field found", bundle: .module)
                                : String(localized: "peek.notRead", defaultValue: "The page wasn’t read", bundle: .module)))
            ResultField(label: String(localized: "peek.age", defaultValue: "Domain age", bundle: .module),
                        value: PeekViewText.age(days: ageDays))
        }
    }
}
