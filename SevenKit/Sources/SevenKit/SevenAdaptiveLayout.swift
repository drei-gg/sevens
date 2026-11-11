import SwiftUI
import SevenKitCore

/// A two-child layout. The same children survive resizing; changing placement never reconstructs the editor state.
public struct SevenAdaptiveColumns: Layout {
    public var accessibilityText: Bool
    public init(accessibilityText: Bool = false) { self.accessibilityText = accessibilityText }
    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let proposed = proposal.width ?? 320
        let width = proposed.isFinite ? max(0, proposed) : 320
        let layout = WorkspaceLayout(width: width, accessibilityText: accessibilityText)
        let sizes = subviews.enumerated().map { index, view in
            view.sizeThatFits(ProposedViewSize(width: index == 0 ? layout.primaryWidth : layout.detailWidth, height: nil))
        }
        let height = layout.arrangement == .columns ? sizes.map(\.height).max() ?? 0
            : sizes.map(\.height).reduce(0, +) + CGFloat(max(0, sizes.count - 1)) * WorkspaceLayout.gap
        return CGSize(width: width, height: height)
    }
    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let layout = WorkspaceLayout(width: bounds.width, accessibilityText: accessibilityText)
        var y = bounds.minY
        for (index, view) in subviews.enumerated() {
            let width = index == 0 ? layout.primaryWidth : layout.detailWidth
            let size = view.sizeThatFits(ProposedViewSize(width: width, height: nil))
            let x = bounds.minX + (layout.arrangement == .columns && index > 0 ? layout.primaryWidth + WorkspaceLayout.gap : 0)
            view.place(at: CGPoint(x: x, y: layout.arrangement == .columns ? bounds.minY : y), anchor: .topLeading,
                       proposal: ProposedViewSize(width: width, height: size.height))
            y += size.height + WorkspaceLayout.gap
        }
    }
}

public extension View {
    /// Uses system navigation bars and safe areas, including vertical system bars on newer iOS.
    @ViewBuilder func sevenAdaptiveTabs() -> some View {
        if #available(iOS 18.0, *) { self.tabViewStyle(.sidebarAdaptable) }
        else { self }
    }
    /// A readable measure for purchase/onboarding copy on wide windows. Backgrounds stay full width.
    func sevenReadableSheet() -> some View {
        frame(maxWidth: 640).frame(maxWidth: .infinity)
    }
}
