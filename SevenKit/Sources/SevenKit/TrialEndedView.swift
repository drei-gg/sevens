import SwiftUI
import SevenKitCore

public struct TrialEndedContent: Sendable, Equatable {
    public var title: String?
    public var stillFree: [String]
    public var unlockKeeps: [String]
    public init(title: String? = nil, stillFree: [String], unlockKeeps: [String]) {
        self.title = title
        self.stillFree = stillFree
        self.unlockKeeps = unlockKeeps
    }
}

/// The one sheet after the trial ends: what stays free, what the unlock keeps, Unlock / Not now.
/// Marks itself shown when it appears, so it never comes back. Present it with
/// `.trialEndedSheet(content:)`.
public struct TrialEndedView: View {
    @Environment(Store.self) private var store
    @Environment(\.dismiss) private var dismiss
    private let content: TrialEndedContent

    public init(content: TrialEndedContent) { self.content = content }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.l) {
                Text(content.title ?? seven("seven.trialEnded.title", "Your free 7 days have ended"))
                    .textRole(.title).foregroundStyle(.primary)
                    .accessibilityAddTraits(.isHeader)
                section(seven("seven.trialEnded.free", "What stays free"), content.stillFree)
                section(seven("seven.trialEnded.keeps", "What the unlock keeps"), content.unlockKeeps)
                VStack(spacing: Tokens.Space.s) {
                    SevenButton(title: store.unlockButtonTitle, brand: true, disabled: !store.canPurchase) {
                        Task { await store.unlock() }
                    }
                    SevenButton(title: seven("seven.notNow", "Not now")) { dismiss() }  // same size and shape as the buy button, which alone is filled (SPEC §13)
                }
            }
            .sevenSheetPadding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .sevenReadableSheet()
        }
        .sevenScreenBackground()
        .onAppear { store.markTrialEndedShown() }
        .onChange(of: store.paidFeaturesAvailable) { _, available in
            if available { dismiss() }
        }
    }

    private func section(_ heading: String, _ lines: [String]) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Text(heading).textRole(.rowTitle, emphasized: true).foregroundStyle(.primary).accessibilityAddTraits(.isHeader)
            SevenListLines(lines: lines)
        }
    }
}

private struct TrialEndedPresenter: ViewModifier {
    @Environment(Store.self) private var store
    @State private var show = false
    let content: TrialEndedContent

    func body(content view: Content) -> some View {
        view
            .task(id: store.hasStarted) {
                // Only at launch: once the store has loaded, not when a trial lapses mid-use.
                if store.hasStarted && store.launchSheet == .trialEnded { show = true }
            }
            .sheet(isPresented: $show) {
                NavigationStack { TrialEndedView(content: content) }
            }
    }
}

extension View {
    /// On the first launch after the trial expires, present `TrialEndedView` once.
    public func trialEndedSheet(content: TrialEndedContent) -> some View {
        modifier(TrialEndedPresenter(content: content))
    }
}

extension TrialEndedContent {
    static let preview = TrialEndedContent(
        stillFree: ["Scanning", "Checking links"],
        unlockKeeps: ["Peek", "Make", "History"]
    )
}

#Preview("Trial ended, light") {
    TrialEndedView(content: .preview).sevenStore(SevenPreviewSupport.store(.expired), unlock: .preview)
}
#Preview("Trial ended, dark") {
    TrialEndedView(content: .preview).sevenStore(SevenPreviewSupport.store(.expired), unlock: .preview)
        .preferredColorScheme(.dark)
}
#Preview("Trial ended, largest text") {
    TrialEndedView(content: .preview).sevenStore(SevenPreviewSupport.store(.expired), unlock: .preview)
        .environment(\.dynamicTypeSize, .accessibility5)
}
