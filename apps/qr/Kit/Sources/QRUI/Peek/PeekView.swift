import SwiftUI
import QRCore
import SevenKit

public struct PeekView: View {
    let model: PeekModel
    @Environment(\.dismiss) private var dismiss
    /// Absent in the share extensions; there the person has no known addresses.
    @Environment(AppModel.self) private var app: AppModel?

    /// The Peek sheet (SPEC §6). The button that presents it is the explicit action, so the
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
        .task {
            if case .idle = model.state { model.start(known: app?.knownAddresses ?? KnownAddresses()) }
        }
        .onDisappear {
            if model.isRunning { model.cancel() }
        }
    }
}
