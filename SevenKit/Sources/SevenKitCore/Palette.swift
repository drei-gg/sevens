import Foundation

/// The Sevens' colors as sRGB hex values, and the WCAG 2 contrast math that gates them.
/// Pure data, so the contrast gate runs on Linux and the Android port can read the same values.
/// `Tokens` (SevenKit) builds SwiftUI colors from these.
public enum Palette {
    /// One color in light and dark appearance.
    public struct Pair: Sendable, Equatable {
        public let light: Int
        public let dark: Int
        public init(light: Int, dark: Int) { self.light = light; self.dark = dark }
    }

    /// Background of our screens and grouped lists.
    public static let paper = Pair(light: 0xF3EFE6, dark: 0x121110)
    /// Our own containers (verdict header, result cards) on paper.
    public static let card = Pair(light: 0xFFFDF8, dark: 0x1C1A18)
    /// The accent: tint, prominent button fill, links.
    public static let ink = Pair(light: 0x161513, dark: 0xEEE9DF)
    /// Labels on an ink fill. Always set explicitly: the system's automatic white on dark-mode ink
    /// is 1.2:1.
    public static let onInk = Pair(light: 0xF3EFE6, dark: 0x161513)
    /// Secondary text, section headers and footers. Replaces system `.secondary`, which is 3.25:1
    /// on paper in light mode.
    public static let muted = Pair(light: 0x55504A, dark: 0xA8A196)
    /// Brand red: the Unlock button, the wordmark 7, the stamp. Never a status color.
    public static let red = Pair(light: 0xD11F16, dark: 0xFF5D50)
    /// Labels on a red fill. White on dark-mode red is 3.0:1, so dark mode uses dark paper.
    public static let onRed = Pair(light: 0xFFFFFF, dark: 0x121110)
    /// Status "Be careful". System orange is 1.9:1 on paper.
    public static let caution = Pair(light: 0x8A4A00, dark: 0xF2A33A)
    /// Status "Likely scam", failed checks, field errors.
    public static let danger = Pair(light: 0xB0201A, dark: 0xFF7468)

    /// System grouped-row backgrounds that our text can also sit on (List and Form rows keep the
    /// system's own row background on paper).
    public static let systemRow = Pair(light: 0xFFFFFF, dark: 0x1C1C1E)

    /// WCAG 2 relative luminance of an sRGB hex color.
    public static func luminance(_ hex: Int) -> Double {
        func channel(_ shift: Int) -> Double {
            let c = Double((hex >> shift) & 0xFF) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /// WCAG 2 contrast ratio, 1...21.
    public static func contrast(_ a: Int, _ b: Int) -> Double {
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// `color` at `alpha` composited over `background` (both opaque sRGB hex).
    public static func over(_ color: Int, alpha: Double, _ background: Int) -> Int {
        func mix(_ shift: Int) -> Int {
            let f = Double((color >> shift) & 0xFF), b = Double((background >> shift) & 0xFF)
            return Int((f * alpha + b * (1 - alpha)).rounded())
        }
        return (mix(16) << 16) | (mix(8) << 8) | mix(0)
    }

    /// A foreground drawn on a background, as the app renders it.
    public struct RenderedPair: Sendable {
        public let name: String
        public let foreground: Pair
        public let background: Pair
    }

    /// Every text-or-icon-on-surface pair the Sevens render. The contrast gate checks each at
    /// 4.5:1 in both appearances. Add a row whenever a new pairing appears in the UI.
    public static var renderedPairs: [RenderedPair] {
        var rows: [RenderedPair] = []
        let surfaces: [(String, Pair)] = [("paper", paper), ("card", card), ("system row", systemRow)]
        let texts: [(String, Pair)] = [("ink", ink), ("muted", muted), ("caution", caution), ("danger", danger)]
        for (t, fg) in texts {
            for (s, bg) in surfaces { rows.append(RenderedPair(name: "\(t) on \(s)", foreground: fg, background: bg)) }
        }
        rows.append(RenderedPair(name: "onInk on ink (prominent button)", foreground: onInk, background: ink))
        rows.append(RenderedPair(name: "onRed on red (Unlock)", foreground: onRed, background: red))
        // `.bordered` buttons: ink label on an ink-tinted fill (about 15% ink) over paper.
        let bordered = Pair(light: over(ink.light, alpha: 0.15, paper.light),
                            dark: over(ink.dark, alpha: 0.15, paper.dark))
        rows.append(RenderedPair(name: "ink on bordered fill", foreground: ink, background: bordered))
        return rows
    }
}
