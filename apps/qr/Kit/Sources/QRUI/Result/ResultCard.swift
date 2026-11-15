import SwiftUI
import SevenKit

/// A card on the paper background. Content stacks vertically so nothing clips at the largest text sizes.
struct ResultCard<Content: View>: View {
    private let title: String?
    private let content: Content

    init(title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.m) {
            if let title {
                Text(title)
                    .textRole(.caption)
                    .accessibilityAddTraits(.isHeader)
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Tokens.Space.m)
        .sevenCard()
    }
}

/// A label above a value. Values are selectable (scanned text is something people compare and copy).
struct ResultField: View {
    let label: String
    let value: String
    var monospaced = false
    var muted = false

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            Text(label).textRole(.caption)
            Text(value)
                .textRole(.body)
                .monospaced(monospaced)
                .foregroundStyle(muted ? Tokens.muted : Color.primary)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
