import SwiftUI
import UIKit

/// Design tokens (PLAN §6.13), matching the site. Light and dark values resolve with the
/// trait collection, so they follow the system appearance.
public enum Tokens {
    // Hex values as documented; kept public so tests (and the site) can compare.
    public enum Hex {
        public static let paper = (light: 0xF3EFE6, dark: 0x121110)
        public static let card = (light: 0xFFFDF8, dark: 0x1C1A18)
        public static let ink = (light: 0x161513, dark: 0xEEE9DF)
        public static let muted = (light: 0x55504A, dark: 0xA8A196)
        public static let red = (light: 0xD11F16, dark: 0xFF5D50)
    }

    public static let paper = Color(light: Hex.paper.light, dark: Hex.paper.dark)
    public static let card = Color(light: Hex.card.light, dark: Hex.card.dark)
    public static let ink = Color(light: Hex.ink.light, dark: Hex.ink.dark)
    public static let muted = Color(light: Hex.muted.light, dark: Hex.muted.dark)
    /// Brand red. Never used for verdicts.
    public static let red = Color(light: Hex.red.light, dark: Hex.red.dark)

    /// Layout constants.
    public enum Space {
        public static let xs: CGFloat = 4
        public static let s: CGFloat = 8
        public static let m: CGFloat = 16
        public static let l: CGFloat = 24
        public static let xl: CGFloat = 32
    }
    public enum Radius {
        public static let card: CGFloat = 14
        public static let button: CGFloat = 12
    }

    /// How a verdict looks. Normal = ink + `checkmark.circle`; careful = system orange +
    /// `exclamationmark.triangle`; likely scam = system red + `xmark.octagon`.
    public enum VerdictStyle: Sendable, CaseIterable {
        case normal, careful, likelyScam

        public var color: Color {
            switch self {
            case .normal: return Tokens.ink
            case .careful: return .orange
            case .likelyScam: return .red
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
    /// A color that switches between two sRGB hex values with the appearance.
    init(light: Int, dark: Int) {
        self.init(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
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
