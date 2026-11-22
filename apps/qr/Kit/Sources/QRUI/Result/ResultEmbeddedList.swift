import SwiftUI
import QRCore
import SevenKit

/// Links found inside text, contacts, events, SMS and email: each with its domain and its own
/// verdict, worst first. A tap shows that link's own result. A long list shows the first
/// `visibleLimit` and a "Show all" button.
struct ResultEmbeddedList: View {
    let embedded: [Analysis]
    @State private var showsAll = false

    nonisolated static let visibleLimit = 10

    /// Worst verdict first; links with the same verdict keep the order they appear in.
    nonisolated static func ordered(_ embedded: [Analysis]) -> [Analysis] {
        embedded.enumerated()
            .sorted { $0.element.level != $1.element.level ? $0.element.level > $1.element.level : $0.offset < $1.offset }
            .map(\.element)
    }

    /// What the list shows: everything, or the first `limit` once it is longer than that.
    nonisolated static func visible(_ embedded: [Analysis], showsAll: Bool, limit: Int = visibleLimit) -> [Analysis] {
        let ordered = ordered(embedded)
        return showsAll || ordered.count <= limit ? ordered : Array(ordered.prefix(limit))
    }

    nonisolated static func domain(of analysis: Analysis) -> String {
        if case .link(let link) = analysis.payload { return ResultLinkDisplay.headline(link) }
        return analysis.code.text ?? ""
    }

    var body: some View {
        if !embedded.isEmpty {
            ResultCard(title: String(localized: "result.embedded.title", defaultValue: "Links inside", bundle: .module)) {
                VStack(spacing: Tokens.Space.s) {
                    ForEach(Array(Self.visible(embedded, showsAll: showsAll).enumerated()), id: \.offset) { _, item in
                        NavigationLink(value: ResultRoute.embedded(item)) {
                            HStack(spacing: Tokens.Space.s) {
                                VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                                    Text(Self.domain(of: item)).textRole(.rowTitle, emphasized: true).foregroundStyle(.primary)
                                        .fixedSize(horizontal: false, vertical: true)
                                    VerdictLabel(level: item.level)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right").foregroundStyle(Tokens.muted).accessibilityHidden(true)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .combine)
                        .accessibilityHint(String(localized: "result.embedded.hint", defaultValue: "Shows this link’s result", bundle: .module))
                    }
                    if !showsAll && embedded.count > Self.visibleLimit {
                        Button {
                            showsAll = true
                        } label: {
                            Text(String(localized: "result.embedded.showAll", defaultValue: "Show all \(embedded.count) links", bundle: .module,
                                        comment: "Under the first ten links found inside a code. The number is how many there are."))
                                .textRole(.rowTitle)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityIdentifier(QRAccessibilityID.resultEmbeddedShowAll)
                    }
                }
            }
        }
    }
}
