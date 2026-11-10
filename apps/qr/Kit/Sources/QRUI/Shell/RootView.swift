import SwiftUI
import QRCore

// Phase 0 placeholder. WP 3.1 replaces this file.

public struct RootView: View {
    public init() {}

    public var body: some View {
        VStack(spacing: 12) {
            Text(String(localized: "app.placeholder.title", defaultValue: "QR Code Kit", bundle: .module))
                .font(.largeTitle.bold())
            Text(verbatim: "\(BundledData.shared.brands.count) brands")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}
