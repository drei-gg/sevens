import SwiftUI
import QRCore
import SevenKit

/// Links found inside text, contacts, events, SMS and email: each with its domain and its own
/// verdict. A tap shows that link's own result.
struct ResultEmbeddedList: View {
    let embedded: [Analysis]

    static func domain(of analysis: Analysis) -> String {
        if case .link(let link) = analysis.payload { return ResultLinkDisplay.headline(link) }
        return analysis.code.text ?? ""
    }

    var body: some View {
        if !embedded.isEmpty {
            ResultCard(title: String(localized: "result.embedded.title", defaultValue: "Links inside", bundle: .module)) {
                VStack(spacing: Tokens.Space.s) {
                    ForEach(Array(embedded.enumerated()), id: \.offset) { _, item in
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
                }
            }
        }
    }
}
