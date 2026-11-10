import SwiftUI
import QRCore
import SevenKit

/// The app's root (SPEC §2, §13): the scan screen, with Make, History and Settings pushed
/// from it. At launch only two things may appear, each once: Before you buy on the very first
/// launch, and the trial-ended sheet on the first launch after the trial expired.
public struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var showFirstLaunch = false

    public init() {}

    public var body: some View {
        NavigationStack {
            ScanScreen()
                .navigationDestination(for: RootRoute.self) { route in
                    switch route {
                    case .make:
                        MakeScreen().gated(feature: RootView.makeFeatureName)
                    case .history:
                        HistoryScreen()
                    case .settings:
                        SettingsScreen()
                    }
                }
        }
        .task(id: model.store.hasStarted) {
            if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { showFirstLaunch = true }
        }
        .sheet(isPresented: $showFirstLaunch) {
            FirstLaunchFlow { showFirstLaunch = false }
                .interactiveDismissDisabled()
        }
        .trialEndedSheet(content: QRGate.trialEndedContent())
    }

    static var makeFeatureName: String {
        String(localized: "root.make.feature", defaultValue: "Make", bundle: .module)
    }
}

#Preview("Root, light") {
    let model = AppModel.preview(state: .unlocked, history: [])
    RootView().qrStore(model)
}
#Preview("Root, dark") {
    let model = AppModel.preview(state: .unlocked, history: [])
    RootView().qrStore(model).preferredColorScheme(.dark)
}
#Preview("Root, largest text") {
    let model = AppModel.preview(state: .unlocked, history: [])
    RootView().qrStore(model).dynamicTypeSize(.accessibility5)
}
