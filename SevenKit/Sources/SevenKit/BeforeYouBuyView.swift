import SwiftUI
import SevenKitCore

/// SPEC §1 step 2, in the app's own words. `{price}` in a paragraph is replaced with the
/// StoreKit display price at runtime (G7: no hard-coded prices). Without a price, the paragraph's
/// `withoutPrice` text is used instead (or `priceFreeFallback`).
public struct BeforeYouBuyContent: Sendable, Equatable {
    public struct Paragraph: Sendable, Equatable, ExpressibleByStringLiteral {
        public var text: String
        public var withoutPrice: String?
        public init(_ text: String, withoutPrice: String? = nil) {
            self.text = text
            self.withoutPrice = withoutPrice
        }
        public init(stringLiteral value: String) { self.init(value) }
    }

    public static let pricePlaceholder = "{price}"

    public var title: String
    public var paragraphs: [Paragraph]
    public var startTrialLabel: String?
    public var justScanLabel: String?
    public var continueLabel: String?
    public var priceFreeFallback: String?

    public init(
        title: String,
        paragraphs: [Paragraph],
        startTrialLabel: String? = nil,
        justScanLabel: String? = nil,
        continueLabel: String? = nil,
        priceFreeFallback: String? = nil
    ) {
        self.title = title
        self.paragraphs = paragraphs
        self.startTrialLabel = startTrialLabel
        self.justScanLabel = justScanLabel
        self.continueLabel = continueLabel
        self.priceFreeFallback = priceFreeFallback
    }

    /// The paragraphs as shown, given the price (nil when products didn't load).
    public func resolvedParagraphs(price: String?) -> [String] {
        paragraphs.map { paragraph in
            guard paragraph.text.contains(Self.pricePlaceholder) else { return paragraph.text }
            if let price { return paragraph.text.replacingOccurrences(of: Self.pricePlaceholder, with: price) }
            return paragraph.withoutPrice ?? priceFreeFallback
                ?? seven("seven.beforeYouBuy.noPrice", "Then a one-time price, if you want to keep it.")
        }
    }
}

public enum BeforeYouBuyChoice: Sendable, Equatable {
    case startedTrial, justScan, continued, done
}

/// First-launch screen, reachable again from Settings. Two buttons of equal weight. With
/// `TrialPolicy.onFirstLaunch` they collapse to "Continue"; once the trial has started (or the
/// unlock is owned) the screen offers just "Done".
public struct BeforeYouBuyView: View {
    @Environment(Store.self) private var store
    private let content: BeforeYouBuyContent
    private let onFinish: (BeforeYouBuyChoice) -> Void

    public init(content: BeforeYouBuyContent, onFinish: @escaping (BeforeYouBuyChoice) -> Void) {
        self.content = content
        self.onFinish = onFinish
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.Space.m) {
                Text(content.title).font(.title.weight(.semibold)).foregroundStyle(Tokens.ink)
                    .accessibilityAddTraits(.isHeader)
                ForEach(Array(content.resolvedParagraphs(price: store.unlockDisplayPrice).enumerated()), id: \.offset) { _, text in
                    Text(text).font(.body).foregroundStyle(Tokens.ink)
                }
                if let error = store.lastError {
                    Text(error).font(.callout).foregroundStyle(Tokens.muted)
                }
            }
            .padding(Tokens.Space.m)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .safeAreaInset(edge: .bottom) { buttons.padding(Tokens.Space.m).background(Tokens.paper) }
        .background(Tokens.paper.ignoresSafeArea())
    }

    private var buttons: some View {
        VStack(spacing: Tokens.Space.s) {
            if store.trialPolicy == .onFirstLaunch {
                SevenButton(title: content.continueLabel ?? seven("seven.beforeYouBuy.continue", "Continue")) {
                    finish(.continued)
                }
            } else if store.state == .notStarted {
                SevenButton(title: content.startTrialLabel ?? seven("seven.trial.start", "Start my free 7 days"),
                            disabled: store.isBusy) {
                    Task {
                        await store.startTrial()
                        if store.state != .notStarted { finish(.startedTrial) }
                    }
                }
                SevenButton(title: content.justScanLabel ?? seven("seven.beforeYouBuy.justScan", "Just scan for free"),
                            disabled: store.isBusy) {
                    finish(.justScan)
                }
            } else {
                SevenButton(title: seven("seven.done", "Done")) { finish(.done) }
            }
        }
    }

    private func finish(_ choice: BeforeYouBuyChoice) {
        store.markBeforeYouBuyShown()
        onFinish(choice)
    }
}

extension BeforeYouBuyContent {
    static let preview = BeforeYouBuyContent(
        title: "Before you buy",
        paragraphs: [
            "Scanning and checking links is free, always.",
            .init("Peek, Make and History cost {price}, once. Try all of it free for 7 days first.",
                  withoutPrice: "Peek, Make and History are a one-time purchase. Try all of it free for 7 days first."),
            "Nothing renews. No card is needed to start.",
        ]
    )
}

#Preview("Before you buy, light") {
    BeforeYouBuyView(content: .preview) { _ in }
        .sevenStore(SevenPreviewSupport.store(.fresh), unlock: .preview)
}
#Preview("Before you buy, dark, no price") {
    BeforeYouBuyView(content: .preview) { _ in }
        .sevenStore(SevenPreviewSupport.store(.fresh, prices: false), unlock: .preview)
        .preferredColorScheme(.dark)
}
#Preview("Before you buy, largest text") {
    BeforeYouBuyView(content: .preview) { _ in }
        .sevenStore(SevenPreviewSupport.store(.fresh), unlock: .preview)
        .environment(\.dynamicTypeSize, .accessibility5)
}
