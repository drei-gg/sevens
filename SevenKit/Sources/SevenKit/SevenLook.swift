import SwiftUI

public extension View {
    /// The Sevens' look at an app's (or extension's) root: ink as the accent everywhere below.
    /// Toggles stay system green: don't tint them (polish guide §3.2 rule 4).
    func sevenLook() -> some View { tint(Tokens.ink) }

    /// Paper behind a `List`, `Form` or `ScrollView` screen. Rows keep the system's grouped-row
    /// background, which `Palette.systemRow` covers in the contrast gate.
    func sevenScreenBackground() -> some View {
        scrollContentBackground(.hidden).background(Tokens.paper.ignoresSafeArea())
    }

    /// Our own container on paper (the verdict header, a result card).
    func sevenCard(cornerRadius: CGFloat = Tokens.Radius.card) -> some View {
        background(Tokens.card, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}
