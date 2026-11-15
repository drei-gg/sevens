import SwiftUI
import SevenKit

/// A finished export waiting to be shared.
struct HistoryExportFile: Identifiable, Equatable {
    let id = UUID()
    let url: URL
}

/// "Your export is ready" with a `ShareLink` for the temporary file. Sharing is the user's tap.
struct HistoryExportSheet: View {
    let file: HistoryExportFile
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Tokens.Space.m) {
                Text(String(localized: "history.export.ready", defaultValue: "Your export is ready.", bundle: .module))
                    .textRole(.section)
                    .foregroundStyle(.primary)
                Text(file.url.lastPathComponent)
                    .textRole(.caption)
                    .textSelection(.enabled)
                ShareLink(item: file.url) {
                    Label(String(localized: "history.export.share", defaultValue: "Share or save the file", bundle: .module),
                          systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .sevenProminentButton()
                .controlSize(.large)
                Spacer(minLength: 0)
            }
            .padding(Tokens.Space.m)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .navigationTitle(String(localized: "history.export.title", defaultValue: "Export", bundle: .module))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "history.export.done", defaultValue: "Done", bundle: .module)) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
