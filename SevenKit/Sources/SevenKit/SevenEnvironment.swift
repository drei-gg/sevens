import SwiftUI

/// What the unlock sheet says. The app passes its own words; SevenKit hard-codes nothing about
/// any one app.
public struct UnlockContent: Sendable, Equatable {
    /// The wordmark slot in the About section: plain text here, replaced by the brand image in branded builds.
    public var appName: String
    /// The "what the unlock adds" lines.
    public var features: [String]
    public var title: String?

    public init(appName: String, features: [String], title: String? = nil) {
        self.appName = appName
        self.features = features
        self.title = title
    }
}

extension EnvironmentValues {
    @Entry var sevenUnlockContent = UnlockContent(appName: "", features: [])
}

extension View {
    /// Put this once near the root. It injects the `Store` (for `@Environment(Store.self)`)
    /// and the text for the unlock sheet that gates present.
    public func sevenStore(_ store: Store, unlock: UnlockContent) -> some View {
        environment(store).environment(\.sevenUnlockContent, unlock)
    }
}
