import Foundation

/// sRGB, components 0…1.
public struct RGBColor: Sendable, Hashable {
    public var red: Double, green: Double, blue: Double
    public init(red: Double, green: Double, blue: Double) {
        self.red = red
        self.green = green
        self.blue = blue
    }
    public static let black = RGBColor(red: 0, green: 0, blue: 0)
    public static let white = RGBColor(red: 1, green: 1, blue: 1)

    /// "#rrggbb", lowercase. Components are clamped to 0…1 and rounded to the nearest 0–255 (NaN reads as 0).
    public var hex: String {
        func byte(_ v: Double) -> Int {
            guard !v.isNaN else { return 0 }
            return Int((min(1, max(0, v)) * 255).rounded())
        }
        let digits = Array("0123456789abcdef")
        var out = "#"
        for v in [red, green, blue] {
            let b = byte(v)
            out.append(digits[b / 16])
            out.append(digits[b % 16])
        }
        return out
    }
}
