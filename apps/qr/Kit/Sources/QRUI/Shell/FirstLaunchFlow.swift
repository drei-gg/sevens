import SwiftUI
import QRCore
import SevenKit

/// Before you buy, shown once at first launch (SPEC §13, D1): SevenKit's screen with two
/// buttons of equal weight, "Start my free 7 days" and "Just scan for free". Either one marks
/// the screen as shown (SevenKit does that) and calls `onFinish`.
///
/// Note: SevenKit's `SevenButton` sets no accessibility identifier, so `firstLaunchTrial` and
/// `firstLaunchFree` cannot be attached from here; UI tests find the buttons by their labels.
public struct FirstLaunchFlow: View {
    let onFinish: () -> Void

    public init(onFinish: @escaping () -> Void) {
        self.onFinish = onFinish
    }

    public var body: some View {
        BeforeYouBuyView(content: QRGate.beforeYouBuyContent()) { _ in onFinish() }
    }
}

#Preview("First launch, light") {
    FirstLaunchFlow {}.qrStore(AppModel.preview(state: .fresh, history: []))
}
#Preview("First launch, dark") {
    FirstLaunchFlow {}.qrStore(AppModel.preview(state: .fresh, history: [])).preferredColorScheme(.dark)
}
#Preview("First launch, largest text") {
    FirstLaunchFlow {}.qrStore(AppModel.preview(state: .fresh, history: [])).dynamicTypeSize(.accessibility5)
}
