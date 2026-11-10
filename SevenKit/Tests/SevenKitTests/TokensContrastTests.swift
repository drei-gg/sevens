import Foundation
import Testing
@testable import SevenKit

/// WCAG AA: text tokens meet WCAG AA (4.5:1) on the paper and card tokens, both themes.
@Suite struct TokensContrastTests {
    private func luminance(_ hex: Int) -> Double {
        func channel(_ shift: Int) -> Double {
            let c = Double((hex >> shift) & 0xFF) / 255
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private func ratio(_ a: Int, _ b: Int) -> Double {
        let (hi, lo) = (max(luminance(a), luminance(b)), min(luminance(a), luminance(b)))
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test func inkAndMutedMeetAAInBothThemes() {
        typealias H = Tokens.Hex
        for (fg, name) in [(H.ink, "ink"), (H.muted, "muted"), (H.red, "red")] {
            for (bg, bgName) in [(H.paper, "paper"), (H.card, "card")] {
                #expect(ratio(fg.light, bg.light) >= 4.5, "\(name) on \(bgName), light")
                #expect(ratio(fg.dark, bg.dark) >= 4.5, "\(name) on \(bgName), dark")
            }
        }
    }

    /// Bar buttons sit on an opaque bar (`SevenBars`): that pair must pass in both themes.
    @Test func barButtonsMeetAAOnTheBarBackground() {
        #expect(ratio(SevenBars.buttons.light, SevenBars.background.light) >= 4.5)
        #expect(ratio(SevenBars.buttons.dark, SevenBars.background.dark) >= 4.5)
    }
}
