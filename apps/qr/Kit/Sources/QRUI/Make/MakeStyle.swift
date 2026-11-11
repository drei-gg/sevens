import QRCore
import SevenKit
import SwiftUI
import UIKit

/// Foreground and background of the generated code (SPEC §8), and the conversions around them.
enum MakeStyle {
    /// A SwiftUI color as sRGB components 0…1 (extended-range and other spaces are converted
    /// and clamped). Opacity is ignored: codes are always opaque.
    static func rgb(_ color: Color) -> RGBColor { rgb(UIColor(color)) }

    static func rgb(_ ui: UIColor) -> RGBColor {
        func clamp(_ v: CGFloat) -> Double { v.isNaN ? 0 : Double(min(1, max(0, v))) }
        if let space = CGColorSpace(name: CGColorSpace.sRGB),
           let converted = ui.cgColor.converted(to: space, intent: .defaultIntent, options: nil),
           let c = converted.components, c.count >= 3 {
            return RGBColor(red: clamp(c[0]), green: clamp(c[1]), blue: clamp(c[2]))
        }
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return RGBColor(red: clamp(r), green: clamp(g), blue: clamp(b))
    }

    static func color(_ rgb: RGBColor) -> Color {
        Color(.sRGB, red: rgb.red, green: rgb.green, blue: rgb.blue, opacity: 1)
    }

    /// One line per warning, in display order.
    static func warningText(_ warning: ContrastWarning) -> String {
        switch warning {
        case .lowContrast(let ratio):
            let value = ratio.formatted(.number.precision(.fractionLength(1)))
            return String(localized: "make.contrast.low",
                          defaultValue: "Low contrast (\(value) to 1). Phones may not be able to scan this code.", bundle: .module)
        case .inverted:
            return String(localized: "make.contrast.inverted",
                          defaultValue: "The code is lighter than its background. Many scanners can't read light-on-dark codes.", bundle: .module)
        }
    }
}

/// The style controls: two color pickers and the contrast warning.
struct MakeStyleSection: View {
    @Binding var foreground: Color
    @Binding var background: Color
    let warnings: [ContrastWarning]

    var body: some View {
        Section {
            ColorPicker(selection: $foreground, supportsOpacity: false) {
                Text(verbatim: String(localized: "make.style.foreground", defaultValue: "Code color", bundle: .module))
            }
            .accessibilityIdentifier("make.style.foreground")
            ColorPicker(selection: $background, supportsOpacity: false) {
                Text(verbatim: String(localized: "make.style.background", defaultValue: "Background color", bundle: .module))
            }
            .accessibilityIdentifier("make.style.background")
            ForEach(warnings, id: \.self) { warning in
                Label(MakeStyle.warningText(warning), systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.primary)
                    .accessibilityIdentifier("make.style.warning")
            }
            if foreground != .black || background != .white {
                Button {
                    foreground = .black
                    background = .white
                } label: {
                    Text(verbatim: String(localized: "make.style.reset", defaultValue: "Back to black on white", bundle: .module))
                }
            }
        } header: {
            Text(verbatim: String(localized: "make.style.header", defaultValue: "Colors", bundle: .module))
                .foregroundStyle(Tokens.muted)
        }
    }
}
