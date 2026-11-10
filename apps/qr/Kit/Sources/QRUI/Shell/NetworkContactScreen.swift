import SwiftUI
import SevenKit

/// SPEC §12: every time the app contacts anyone. A readable list, not a table, so it works at
/// the largest text sizes.
public struct NetworkContactScreen: View {
    public init() {}

    public var body: some View {
        List {
            Section {
                Text(String(localized: "network.intro", defaultValue: "This is the complete list of when this app contacts anyone.", bundle: .module))
                    .foregroundStyle(Tokens.ink)
            }
            Section {
                ForEach(Array(NetworkContactContent.entries().enumerated()), id: \.offset) { _, entry in
                    VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                        Text(entry.when).font(.headline).foregroundStyle(Tokens.ink)
                        line(String(localized: "network.who", defaultValue: "Who we contact", bundle: .module), entry.who)
                        line(String(localized: "network.sent", defaultValue: "What's sent", bundle: .module), entry.sent)
                    }
                    .padding(.vertical, Tokens.Space.xs)
                    .accessibilityElement(children: .combine)
                }
            }
            Section {
                Text(NetworkContactContent.closing).font(.body.weight(.semibold)).foregroundStyle(Tokens.ink)
                Text(NetworkContactContent.privacyLabel).foregroundStyle(Tokens.ink)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Tokens.paper.ignoresSafeArea())
        .navigationTitle(String(localized: "network.title", defaultValue: "Network contact", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func line(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label).font(.footnote).foregroundStyle(Tokens.muted)
            Text(value).font(.body).foregroundStyle(Tokens.ink)
        }
    }
}

#Preview("Network contact, light") {
    NavigationStack { NetworkContactScreen() }
}
#Preview("Network contact, dark") {
    NavigationStack { NetworkContactScreen() }.preferredColorScheme(.dark)
}
#Preview("Network contact, largest text") {
    NavigationStack { NetworkContactScreen() }.environment(\.dynamicTypeSize, .accessibility5)
}
