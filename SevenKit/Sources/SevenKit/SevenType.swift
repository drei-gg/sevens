import SwiftUI

/// The Sevens' type system (polish guide §4): feature code names a role, never a text style, so
/// every screen draws from the same few sizes and Dynamic Type works everywhere.
///
/// Two weights only: regular, and bold for `hero`, `title`, `section` and emphasized rows. System
/// controls keep their own label weight.
public enum TextRole: Sendable, CaseIterable {
    /// A live value that is the whole point of the screen: a running timer, a day's total.
    /// Rounded semibold, 56 pt at the default size, scaling with Dynamic Type from `.largeTitle`.
    /// Tool screens only, one per screen; pair it with `.numeric()` when it changes.
    case display
    /// The BigValue: a domain, an amount, a network name. One per screen. Bold `.largeTitle`.
    case hero
    /// Brand moment and empty-state headings, screen titles outside the nav bar. Bold `.title`.
    case title
    /// Headings in our own layouts; the verdict word on a result. Bold `.title3`.
    case section
    /// Primary text of a row or card. `.body`; bold when `emphasized`.
    case rowTitle
    /// Paragraphs and values. `.body`.
    case body
    /// Supporting text: subtitles, row details. `.subheadline` in `muted`.
    case secondary
    /// Metadata, field labels, timestamps, footnotes. `.footnote` in `muted`. Nothing smaller.
    case caption

    var font: Font {
        switch self {
        case .display: return .largeTitle.weight(.semibold)  // Sized by `TextRoleModifier`.
        case .hero: return .largeTitle.weight(.bold)
        case .title: return .title.weight(.bold)
        case .section: return .title3.weight(.bold)
        case .rowTitle, .body: return .body
        case .secondary: return .subheadline
        case .caption: return .footnote
        }
    }

    /// Whether the role carries its own color (`muted`). The others inherit (`.primary`).
    var isMuted: Bool { self == .secondary || self == .caption }
}

struct TextRoleModifier: ViewModifier {
    let role: TextRole
    let emphasized: Bool

    /// `display` is 56 pt at the default size and scales like `.largeTitle`.
    @ScaledMetric(relativeTo: .largeTitle) private var displaySize: CGFloat = 56

    func body(content: Content) -> some View {
        let base = role == .display ? Font.system(size: displaySize, weight: .semibold, design: .rounded) : role.font
        let font = emphasized ? base.weight(.bold) : base
        let styled = content.font(font)
        // No Dynamic Type cap, not even on `hero`: the accessibility audit fails a capped text
        // ("Dynamic Type font sizes are partially unsupported"). Long heroes wrap instead.
        if role.isMuted {
            styled.foregroundStyle(Tokens.muted)
        } else {
            styled
        }
    }
}

public extension View {
    /// Sets text (or an SF Symbol) to one of the Sevens' type roles. `secondary` and `caption`
    /// also set `muted`; to give them another color, put `.foregroundStyle` *before* this call
    /// (the innermost style wins).
    func textRole(_ role: TextRole, emphasized: Bool = false) -> some View {
        modifier(TextRoleModifier(role: role, emphasized: emphasized))
    }

    /// An empty-state or status glyph (an SF Symbol): large, hierarchical, `muted`, hidden from
    /// VoiceOver (the words next to it carry the meaning).
    func glyph() -> some View {
        font(.largeTitle).imageScale(.large).symbolRenderingMode(.hierarchical)
            .foregroundStyle(Tokens.muted).accessibilityHidden(true)
    }

    /// Tabular digits for numbers that change or sit in a column (prices, counts, dates in rows).
    func numeric() -> some View { monospacedDigit() }
}

/// A ruled section header for our own (non-`List`) layouts (polish guide §4.3, KC10 step 2): the
/// `section` role over a 1 pt `rule` hairline across the content width.
public struct SectionRule: View {
    private let title: String

    public init(_ title: String) { self.title = title }

    public var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Text(title).textRole(.section).accessibilityAddTraits(.isHeader)
            Rectangle().fill(Tokens.rule).frame(height: 1).accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
