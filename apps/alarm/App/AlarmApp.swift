import SwiftUI
import AlarmUI

@main struct AlarmApp: App {
    @State private var model = AlarmModel.shared
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup { AlarmRootView(model:model).task { await model.start() }
            .preferredColorScheme(appearance)
        }.onChange(of:scenePhase) { _, phase in if phase == .active { Task { await model.resume() } } }
    }
    private var appearance: ColorScheme? {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-alarm.dark") { return .dark }
        if ProcessInfo.processInfo.arguments.contains("-alarm.light") { return .light }
        #endif
        return nil
    }
}

import AppIntents
struct AlarmAppIntents: AppIntentsPackage {
    static var includedPackages: [any AppIntentsPackage.Type] { [LoopIntentsPackage.self] }
}
