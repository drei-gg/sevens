import SwiftUI
import UIKit

/// Layout constants for every screen, and the brand colors for the About screen alone (SPEC
/// "Look"). Everywhere else the app uses the system's own colors, bars and controls.
public enum Tokens {
    /// Brand hex values, light and dark. Only `AboutSection.swift` may use the colors built from
    /// them (`scripts/check-rules` enforces it).
    public enum Hex {
        public static let paper = (light: 0xF3EFE6, dark: 0x121110)
        public static let ink = (light: 0x161513, dark: 0xEEE9DF)
        public static let muted = (light: 0x55504A, dark: 0xA8A196)
    }

    public static let paper = Color(light: Hex.paper.light, dark: Hex.paper.dark)
    public static let ink = Color(light: Hex.ink.light, dark: Hex.ink.dark)
    public static let muted = Color(light: Hex.muted.light, dark: Hex.muted.dark)

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

    /// How a verdict looks, in the system's own colors. Normal = `.secondary` + `checkmark.circle`;
    /// careful = `.orange` + `exclamationmark.triangle`; likely scam = `.red` + `xmark.octagon`.
    /// The color is for the icon only; the verdict word stays `.primary`. Never color alone.
    public enum VerdictStyle: Sendable, CaseIterable {
        case normal, careful, likelyScam

        public var color: Color {
            switch self {
            case .normal: return .secondary
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
