import SwiftUI
import UIKit

/// Raises the screen brightness while it is in a window, then puts it back. Put it in
/// `.background`. Extensions may be denied; then nothing changes and nothing breaks.
struct ExtensionBrightness: UIViewRepresentable {
    func makeUIView(context: Context) -> ProbeView { ProbeView() }
    func updateUIView(_ uiView: ProbeView, context: Context) {}
    static func dismantleUIView(_ uiView: ProbeView, coordinator: ()) { uiView.restore() }

    final class ProbeView: UIView {
        private var screen: UIScreen?
        private var original: CGFloat?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            isAccessibilityElement = false
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let scene = window?.windowScene {
                raise(scene.screen)
            } else {
                restore()
            }
        }

        private func raise(_ target: UIScreen) {
            guard original == nil else { return }
            screen = target
            original = target.brightness
            target.brightness = 1
        }

        func restore() {
            if let screen, let original { screen.brightness = original }
            screen = nil
            original = nil
        }
    }
}
