import Foundation

public enum ContrastWarning: Sendable, Hashable {
    /// WCAG ratio under 4.0.
    case lowContrast(ratio: Double)
    /// Foreground lighter than background.
    case inverted
}

public enum Contrast {
    /// WCAG 2 contrast ratio, 1…21.
    public static func ratio(_ a: RGBColor, _ b: RGBColor) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// Warnings in display order (low contrast, then inverted); empty when fine. Warns, never blocks.
    public static func warnings(foreground: RGBColor, background: RGBColor) -> [ContrastWarning] {
        var out: [ContrastWarning] = []
        let r = ratio(foreground, background)
        if r < 4.0 { out.append(.lowContrast(ratio: r)) }
        if luminance(foreground) > luminance(background) { out.append(.inverted) }
        return out
    }

    /// WCAG 2.x relative luminance.
    static func luminance(_ c: RGBColor) -> Double {
        func lin(_ v: Double) -> Double {
            let x = v.isNaN ? 0 : min(1, max(0, v))
            return x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
}
