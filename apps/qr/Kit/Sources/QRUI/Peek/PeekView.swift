import SwiftUI
import QRCore
import SevenKit

public struct PeekView: View {
    let model: PeekModel
    @Environment(\.dismiss) private var dismiss

    /// The Peek sheet (PLAN §6.5). The button that presents it is the explicit action, so the
    /// fetch starts when it appears, only if the model is idle.
    public init(model: PeekModel) {
        self.model = model
    }

    public var body: some View {
        NavigationStack {
            PeekViewContent(original: model.original, state: model.state, capture: model.capture, ageDays: model.ageDays)
                .navigationTitle(String(localized: "peek.title.nav", defaultValue: "Peek", bundle: .module))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    if model.isRunning {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(String(localized: "peek.cancel", defaultValue: "Cancel", bundle: .module)) {
                                model.cancel()
                                dismiss()
                            }
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(String(localized: "peek.done", defaultValue: "Done", bundle: .module)) { dismiss() }
                    }
                }
        }
        .tint(Tokens.ink)
        .task {
            if case .idle = model.state { model.start() }
        }
        .onDisappear {
            if model.isRunning { model.cancel() }
        }
    }
}
