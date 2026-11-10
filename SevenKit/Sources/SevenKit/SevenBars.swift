import SwiftUI
import UIKit

/// Navigation bar chrome for every screen, sheets included (SPEC §14). On a phone the system
/// draws bar buttons over a translucent material, so their measured contrast depends on
/// whatever scrolls underneath and fails the accessibility audit. One opaque bar in the paper
/// token with ink buttons gives a fixed pair that `TokensContrastTests` checks in both themes.
/// UIKit appearance reaches every navigation bar made after `install()`, including those
/// inside sheets, which SwiftUI modifiers on the root do not.
public enum SevenBars {
    /// Colours of bar background and bar buttons, as hex pairs.
    public static let background = Tokens.Hex.paper
    public static let buttons = Tokens.Hex.ink

    /// Call once at launch, before the first navigation bar is created. Safe to call twice.
    @MainActor public static func install() {
        let bar = UINavigationBarAppearance()
        bar.configureWithOpaqueBackground()
        bar.backgroundColor = UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? background.dark : background.light)
        }
        bar.shadowColor = .clear
        let ink = UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? buttons.dark : buttons.light)
        }
        bar.titleTextAttributes = [.foregroundColor: ink]
        bar.largeTitleTextAttributes = [.foregroundColor: ink]
        for item in [bar.buttonAppearance, bar.doneButtonAppearance, bar.backButtonAppearance] {
            item.normal.titleTextAttributes = [.foregroundColor: ink]
        }
        let proxy = UINavigationBar.appearance()
        proxy.standardAppearance = bar
        proxy.scrollEdgeAppearance = bar
        proxy.compactAppearance = bar
        proxy.compactScrollEdgeAppearance = bar
        proxy.tintColor = ink
    }
}
