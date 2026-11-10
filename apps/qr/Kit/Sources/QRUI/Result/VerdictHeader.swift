import SwiftUI
import QRCore
import SevenKit

/// The verdict at the top of a result: icon + word + payload type. Never colour alone (G7).
/// One accessibility element whose label is the whole verdict as a sentence.
struct VerdictHeader: View {
    let level: Level
    let typeName: String
    /// Everything that feeds the spoken sentence (info signals are dropped by `VerdictText`).
    let signals: [Signal]
    var identifier: String = QRAccessibilityID.resultVerdict

    var body: some View {
        let style = VerdictText.style(for: level)
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .center, spacing: Tokens.Space.m) {
                icon(style)
                titles
            }
            VStack(alignment: .leading, spacing: Tokens.Space.s) {
                icon(style)
                titles
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Tokens.Space.m)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: Tokens.Radius.card))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(VerdictText.accessibilitySentence(level: level, typeName: typeName, signals: signals))
        .accessibilityIdentifier(identifier)
        .accessibilityAddTraits(.isHeader)
    }

    private func icon(_ style: Tokens.VerdictStyle) -> some View {
        Image(systemName: style.symbolName)
            .font(.largeTitle)
            .foregroundStyle(style.color)
    }

    private var titles: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(VerdictText.title(for: level))
                .font(.title.bold())
                .foregroundStyle(.primary)
            Text(typeName)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .fixedSize(horizontal: false, vertical: true)
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
        .font(.subheadline.weight(.semibold))
    }
}
