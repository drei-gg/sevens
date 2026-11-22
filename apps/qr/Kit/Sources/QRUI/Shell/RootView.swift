import SwiftUI
import QRCore
import SevenKit

/// The app's root (SPEC §2, §13): the scan screen, with Make, History and Settings pushed
/// from it. At launch only two things may appear, each once: Before you buy on the very first
/// launch, and the trial-ended sheet on the first launch after the trial expired.
public struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var showFirstLaunch = false
    @State private var showTrialEnded = false

    public init() {}

    public var body: some View {
        NavigationStack {
            ScanScreen(holdsCamera: launchSheetHoldsCamera)
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
        .sevenLook()
        .task(id: model.store.hasStarted) {
            guard model.store.hasStarted else { return }
            switch model.store.launchSheet {
            case .beforeYouBuy: showFirstLaunch = true
            case .trialEnded: showTrialEnded = true
            case .none: break
            }
        }
        .sheet(isPresented: $showFirstLaunch) {
            FirstLaunchFlow { showFirstLaunch = false }
                .interactiveDismissDisabled()
        }
        // SevenKit's `.trialEndedSheet` presents the same view; presented here so the scanner
        // under it knows it is covered. TrialEndedView marks itself shown when it appears.
        .sheet(isPresented: $showTrialEnded) {
            NavigationStack { TrialEndedView(content: QRGate.trialEndedContent()) }
        }
    }

    /// SPEC §1.3: no camera, and no camera prompt, while a launch sheet is up or about to be.
    /// Before you buy is known from the first frame; the trial-ended sheet once the store has
    /// read the entitlement.
    private var launchSheetHoldsCamera: Bool {
        ScanCameraGate.launchHolds(beforeYouBuyUp: showFirstLaunch, trialEndedUp: showTrialEnded,
                                   beforeYouBuyDue: model.store.shouldPresentBeforeYouBuy,
                                   trialEndedDue: model.store.hasStarted && model.store.launchSheet == .trialEnded)
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
