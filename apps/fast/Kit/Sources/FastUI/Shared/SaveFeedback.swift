import SwiftUI

/// Editors show failed saves inline so feedback remains visible above a presented sheet.
struct SaveFeedback: View {
    @Environment(FastModel.self) private var model
    var body: some View {
        if let message = model.message {
            Section {
                Label(f("Not saved"), systemImage: "exclamationmark.circle").foregroundStyle(.primary)
                Text(message).foregroundStyle(.secondary)
            }
        }
    }
}
