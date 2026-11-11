import SwiftUI
import SevenKit

public struct FastRootView: View {
    @Environment(FastModel.self) private var model
    @State private var firstLaunch = false
    public init() {}
    public var body: some View {
        @Bindable var model = model
        Group {
            if model.isReady {
                TabView {
                    NavigationStack { TodayScreen() }.tabItem { Label(f("Today"), systemImage: "timer") }
                    NavigationStack { HistoryScreen() }.tabItem { Label(f("History"), systemImage: "calendar") }
                    NavigationStack { JournalScreen() }.tabItem { Label(f("Journal"), systemImage: "book.closed") }
                    NavigationStack { TrendsScreen() }.tabItem { Label(f("Trends"), systemImage: "chart.xyaxis.line") }
                }.sevenAdaptiveTabs()
            } else if let failure = model.loadFailure {
                ContentUnavailableView {
                    Label(f("Your records need attention"), systemImage: "externaldrive.badge.exclamationmark")
                } description: { Text(failure) } actions: {
                    Button(f("Try again")) { Task { await model.retryLoad() } }
                    NavigationStack { BackupScreen() }.frame(maxHeight: 250)
                }
            } else { ProgressView(f("Opening your records")) }
        }
        .task(id: model.store.hasStarted) {
            if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { firstLaunch = true }
        }
        .sheet(isPresented: $firstLaunch) {
            BeforeYouBuyView(content: FastGate.before) { _ in firstLaunch = false }.interactiveDismissDisabled()
        }
        .onChange(of: model.store.state) { _, _ in model.publishWatch() }
        .trialEndedSheet(content: FastGate.expired)
        .alert(f("Could not save"), isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button(f("OK"), role: .cancel) { model.message = nil }
        } message: { Text(model.message ?? "") }
    }
}
