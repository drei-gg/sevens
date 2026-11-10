import SwiftUI
import QRUI

@main
struct QRApp: App {
    @State private var model = AppModel(bundle: .main)
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .qrStore(model)
                .task { await model.runLaunch() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: Task { await model.sceneDidBecomeActive() }
            case .background: Task { await model.sceneDidEnterBackground() }
            default: break
            }
        }
    }
}
