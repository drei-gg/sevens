import SwiftUI
import FastUI
import UIKit

@main
struct FastApp: App {
    @UIApplicationDelegateAdaptor(FastAppDelegate.self) private var delegate
    private var model: FastModel { delegate.model }
    @Environment(\.scenePhase) private var scenePhase
    private var testAppearance: ColorScheme? {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-fast.dark") { return .dark }
        if args.contains("-fast.light") { return .light }
        #endif
        return nil
    }
    var body: some Scene {
        WindowGroup { FastRootView().fastStore(model).preferredColorScheme(testAppearance).task { await model.start() } }
            .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await model.resume() } } }
    }
}

/// Activate the companion when iOS launches us for a Watch request, before a window appears.
@MainActor final class FastAppDelegate: NSObject, UIApplicationDelegate {
    let model = FastModel(bundle: .main)
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Task { await model.start() }
        return true
    }
}
