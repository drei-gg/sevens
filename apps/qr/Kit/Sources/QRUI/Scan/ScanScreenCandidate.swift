import QRCore
import SevenKit
import SwiftUI

/// What the scan screen presents in the result sheet.
struct ScanScreenResult: Identifiable {
    let id = UUID()
    let analysis: Analysis
}

/// One row of the pick list shown when a photo or the clipboard holds several codes.
struct ScanScreenCandidate: Identifiable {
    let id: Int
    let analysis: Analysis
    let typeName: String
    /// For links: the registrable domain, else the host.
    let domain: String?

    init(index: Int, analysis: Analysis) {
        id = index
        self.analysis = analysis
        typeName = VerdictText.typeName(for: analysis.payload.typeID)
        if case .link(let info) = analysis.payload {
            let shown = info.registrableDomain ?? info.hostUnicode
            domain = shown.isEmpty ? nil : shown
        } else {
            domain = nil
        }
    }

    var rowLabel: String {
        if let domain { return "\(typeName), \(domain)" }
        return typeName
    }
}

/// The pick list sheet. Choosing a row hands it back through `onPick`; the screen presents the
/// result once this sheet is gone.
struct ScanScreenPickList: View {
    let candidates: [ScanScreenCandidate]
    let onPick: (ScanScreenCandidate) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List(candidates) { candidate in
                Button { onPick(candidate) } label: {
                    VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                        Text(candidate.typeName).font(.headline).foregroundStyle(Tokens.ink)
                        if let domain = candidate.domain {
                            Text(domain).font(.subheadline).foregroundStyle(Tokens.muted)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityLabel(candidate.rowLabel)
                .listRowBackground(Tokens.card)
            }
            .scrollContentBackground(.hidden)
            .background(Tokens.paper)
            .navigationTitle(String(localized: "scan.pick.title", defaultValue: "Pick a code", bundle: .module))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "scan.pick.cancel", defaultValue: "Cancel", bundle: .module), action: onCancel)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
