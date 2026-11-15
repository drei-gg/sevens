import SwiftUI
import UIKit
import SevenKitCore

/// The Sevens' look: colors built from `SevenKitCore.Palette` (where the contrast gate lives), and
/// layout constants. Ink is the app-wide accent and paper the background of our screens (K17),
/// under the contrast rules in the brand repo's polish guide §3.2: labels on ink and red fills use
/// `onInk`/`onRed`, secondary text uses `muted` (never system `.secondary`), statuses use
/// `caution`/`danger` (never system orange/red), toggles stay untinted.
public enum Tokens {
    /// Brand hex values, light and dark (from `Palette`).
    public enum Hex {
        public static let paper = (light: Palette.paper.light, dark: Palette.paper.dark)
        public static let card = (light: Palette.card.light, dark: Palette.card.dark)
        public static let ink = (light: Palette.ink.light, dark: Palette.ink.dark)
        public static let onInk = (light: Palette.onInk.light, dark: Palette.onInk.dark)
        public static let muted = (light: Palette.muted.light, dark: Palette.muted.dark)
        public static let red = (light: Palette.red.light, dark: Palette.red.dark)
        public static let onRed = (light: Palette.onRed.light, dark: Palette.onRed.dark)
        public static let caution = (light: Palette.caution.light, dark: Palette.caution.dark)
        public static let danger = (light: Palette.danger.light, dark: Palette.danger.dark)
        public static let dataWarm = (light: Palette.dataWarm.light, dark: Palette.dataWarm.dark)
        public static let dataCool = (light: Palette.dataCool.light, dark: Palette.dataCool.dark)
    }

    /// Background of our screens and grouped lists.
    public static let paper = Color(Palette.paper)
    /// Our own containers on paper (verdict header, result cards).
    public static let card = Color(Palette.card)
    /// The accent. Applied once at the root with `.sevenLook()`.
    public static let ink = Color(Palette.ink)
    /// Labels on ink fills. Set explicitly: the system's automatic white fails on dark-mode ink.
    public static let onInk = Color(Palette.onInk)
    /// Secondary text, section headers and footers, decorative icons.
    public static let muted = Color(Palette.muted)
    /// Brand red: the Unlock button, the wordmark 7, the stamp. Never a status.
    public static let red = Color(Palette.red)
    /// Labels on the red Unlock fill.
    public static let onRed = Color(Palette.onRed)
    /// Status "Be careful".
    public static let caution = Color(Palette.caution)
    /// Status "Likely scam", failed checks, field errors.
    public static let danger = Color(Palette.danger)
    /// Data hue for time: progress rings and chart marks. Never a tint, button fill or status.
    public static let dataWarm = Color(Palette.dataWarm)
    /// Data hue for water: fills and chart marks. Never a tint, button fill or status.
    public static let dataCool = Color(Palette.dataCool)
    /// Hairlines and decorative outlines (not text). Stronger with Increase Contrast.
    public static let rule = Color(uiColor: UIColor { traits in
        let high = traits.accessibilityContrast == .high
        let hex = traits.userInterfaceStyle == .dark
            ? (high ? Palette.ink.darkHC : Palette.ink.dark)
            : (high ? Palette.ink.lightHC : Palette.ink.light)
        return UIColor(hex: hex).withAlphaComponent(high ? Palette.ruleAlpha.highContrast : Palette.ruleAlpha.normal)
    })

    /// Layout constants.
    public enum Space {
        public static let xs: CGFloat = 4
        public static let s: CGFloat = 8
        public static let ms: CGFloat = 12
        public static let m: CGFloat = 16
        public static let l: CGFloat = 24
        public static let xl: CGFloat = 32
        public static let xxl: CGFloat = 48
    }
    public enum Radius {
        public static let card: CGFloat = 14
        public static let button: CGFloat = 12
    }

    /// How a verdict looks. Normal = `muted` + `checkmark.circle`; careful = `caution` +
    /// `exclamationmark.triangle`; likely scam = `danger` + `xmark.octagon`. The color is for the
    /// icon only; the verdict word stays `.primary`. Never color alone.
    public enum VerdictStyle: Sendable, CaseIterable {
        case normal, careful, likelyScam

        public var color: Color {
            switch self {
            case .normal: return Tokens.muted
            case .careful: return Tokens.caution
            case .likelyScam: return Tokens.danger
            }
        }

        public var symbolName: String {
            switch self {
            case .normal: return "checkmark.circle"
            case .careful: return "exclamationmark.triangle"
            case .likelyScam: return "xmark.octagon"
            }
        }
    }
}

extension Color {
    /// A palette pair as a color that follows the appearance and Increase Contrast.
    init(_ pair: Palette.Pair) {
        self.init(uiColor: UIColor { traits in
            let high = traits.accessibilityContrast == .high
            if traits.userInterfaceStyle == .dark { return UIColor(hex: high ? pair.darkHC : pair.dark) }
            return UIColor(hex: high ? pair.lightHC : pair.light)
        })
    }
}

extension UIColor {
    convenience init(hex: Int) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}
