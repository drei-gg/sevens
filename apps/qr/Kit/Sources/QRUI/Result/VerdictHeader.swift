import SwiftUI
import QRCore
import SevenKit

/// The verdict at the top of a result, stated big and plainly the way the leading scanner apps
/// do: a large status symbol, the verdict word as the screen's hero, the payload type under it.
/// Never colour alone (G7): the colour is on the symbol, the word stays the primary text colour.
/// One accessibility element whose label is the whole verdict as a sentence.
struct VerdictHeader: View {
    let level: Level
    let typeName: String
    /// Everything that feeds the spoken sentence (info signals are dropped by `VerdictText`).
    let signals: [Signal]
    var identifier: String = QRAccessibilityID.resultVerdict

    var body: some View {
        let style = VerdictText.style(for: level)
        VStack(spacing: Tokens.Space.s) {
            Image(systemName: style.symbolName + ".fill")
                .textRole(.display)
                .foregroundStyle(style.color)
                .padding(.bottom, Tokens.Space.xs)
            Text(VerdictText.title(for: level))
                .textRole(.hero)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.6)
            Text(typeName)
                .textRole(.caption)
                .multilineTextAlignment(.center)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity)
        .padding(.vertical, Tokens.Space.l)
        .padding(.horizontal, Tokens.Space.m)
        .sevenCard()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(VerdictText.accessibilitySentence(level: level, typeName: typeName, signals: signals))
        .accessibilityIdentifier(identifier)
        .accessibilityAddTraits(.isHeader)
    }
}

/// A small verdict (icon + word) for rows.
struct VerdictLabel: View {
    let level: Level

    var body: some View {
        let style = VerdictText.style(for: level)
        Label {
            Text(VerdictText.title(for: level)).foregroundStyle(.primary)
        } icon: {
            Image(systemName: style.symbolName).foregroundStyle(style.color)
        }
        .textRole(.rowTitle, emphasized: true)
    }
}
