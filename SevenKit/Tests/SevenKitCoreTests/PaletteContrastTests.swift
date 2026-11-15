import Testing
@testable import SevenKitCore

/// The contrast gate for the Sevens' look: every pair the apps render passes WCAG AA for text
/// (4.5:1) in light and dark. Runs on Linux.
@Suite struct PaletteContrastTests {
    @Test(arguments: Palette.renderedPairs.map(\.name))
    func renderedPairMeetsAA(_ name: String) throws {
        let pair = try #require(Palette.renderedPairs.first { $0.name == name })
        let light = Palette.contrast(pair.foreground.light, pair.background.light)
        let dark = Palette.contrast(pair.foreground.dark, pair.background.dark)
        #expect(light >= 4.5, "\(name), light: \(light)")
        #expect(dark >= 4.5, "\(name), dark: \(dark)")
    }

    /// Increase Contrast raises every pair to WCAG AAA (7:1), and never lowers one.
    @Test(arguments: Palette.renderedPairs.map(\.name))
    func renderedPairMeetsAAAWithIncreaseContrast(_ name: String) throws {
        let pair = try #require(Palette.renderedPairs.first { $0.name == name })
        let light = Palette.contrast(pair.foreground.lightHC, pair.background.lightHC)
        let dark = Palette.contrast(pair.foreground.darkHC, pair.background.darkHC)
        #expect(light >= 7, "\(name), light HC: \(light)")
        #expect(dark >= 7, "\(name), dark HC: \(dark)")
        #expect(light >= Palette.contrast(pair.foreground.light, pair.background.light) - 0.01, "\(name) light")
        #expect(dark >= Palette.contrast(pair.foreground.dark, pair.background.dark) - 0.01, "\(name) dark")
    }

    /// The failures that kept the ink tint out of QR stay failures, so nobody brings them back.
    @Test func knownBadPairsStayBad() {
        // System white label on dark-mode ink.
        #expect(Palette.contrast(0xFFFFFF, Palette.ink.dark) < 1.5)
        // White on dark-mode red.
        #expect(Palette.contrast(0xFFFFFF, Palette.red.dark) < 4.5)
        // System secondary label (60% of #3C3C43) on light paper.
        #expect(Palette.contrast(Palette.over(0x3C3C43, alpha: 0.6, Palette.paper.light), Palette.paper.light) < 4.5)
        // System orange on light paper.
        #expect(Palette.contrast(0xFF9500, Palette.paper.light) < 4.5)
    }

    @Test func contrastMathMatchesWCAG() {
        #expect(abs(Palette.contrast(0x000000, 0xFFFFFF) - 21) < 0.001)
        #expect(Palette.contrast(0x777777, 0x777777) == 1)
        #expect(Palette.over(0x000000, alpha: 0.5, 0xFFFFFF) == 0x808080)
    }

    @Test func brandRedIsNotAStatusColor() {
        #expect(Palette.red != Palette.danger)
    }
}
