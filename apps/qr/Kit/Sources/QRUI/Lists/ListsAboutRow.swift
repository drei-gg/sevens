import SwiftUI
import SevenKit

/// Settings › About: "Scam lists from …" and Check for newer lists (SPEC §4.3). Rows for the
/// About section. Nothing here fetches anything until the button is tapped; a build without a
/// configured key shows the date only. Free forever: no gate.
struct ListsAboutRow: View {
    @State private var model: ListsAboutModel

    init() {
        _model = State(initialValue: ListsAboutModel())
    }

    init(model: ListsAboutModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        Group {
            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                Text(ListsAboutText.title(for: model.lists)).foregroundStyle(.primary)
                    .accessibilityIdentifier(ListsAccessibilityID.title)
                if ListsAboutText.isOld(built: model.lists.built) {
                    Text(ListsAboutText.oldLine).font(.footnote).foregroundStyle(.secondary)
                        .accessibilityIdentifier(ListsAccessibilityID.old)
                }
            }
            .accessibilityElement(children: .combine)

            if model.canCheck {
                Button {
                    Task { await model.check() }
                } label: {
                    HStack(spacing: Tokens.Space.s) {
                        Text(model.isChecking ? ListsAboutText.checking : ListsAboutText.checkButton)
                        if model.isChecking { ProgressView() }
                    }
                }
                .disabled(model.isChecking)
                .accessibilityIdentifier(ListsAccessibilityID.check)
            }

            if case .finished(let sentence) = model.phase {
                Text(sentence).font(.callout).foregroundStyle(.secondary)
                    .accessibilityIdentifier(ListsAccessibilityID.result)
            }

            if model.usingDownloaded {
                Button(ListsAboutText.resetButton) { model.resetToBundled() }
                    .disabled(model.isChecking)
                    .accessibilityIdentifier(ListsAccessibilityID.reset)
            }
        }
    }
}
