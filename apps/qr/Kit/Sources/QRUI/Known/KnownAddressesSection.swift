import SwiftUI
import QRCore
import SevenKit

/// Settings: "Addresses you know" (SPEC §4.4). A row that pushes the list.
struct KnownAddressesSection: View {
    @Environment(AppModel.self) private var model

    init() {}

    var body: some View {
        Section {
            NavigationLink {
                KnownAddressesList()
            } label: {
                HStack {
                    Text(String(localized: "settings.known.row", defaultValue: "Addresses you know", bundle: .module))
                    Spacer()
                    Text(model.knownAddresses.entries.count.formatted())
                        .foregroundStyle(Tokens.muted)
                        .accessibilityHidden(true)
                }
                .accessibilityElement(children: .combine)
            }
            .accessibilityIdentifier(QRAccessibilityID.knownRow)
        } footer: {
            Text(String(localized: "settings.known.footer",
                        defaultValue: "Addresses you told us you know stop getting list-based warnings. Every other check still runs.",
                        bundle: .module))
                .foregroundStyle(Tokens.muted)
        }
    }
}

/// The list: swipe to remove, "Forget all" with a confirmation.
struct KnownAddressesList: View {
    @Environment(AppModel.self) private var model
    @State private var confirmForgetAll = false

    var body: some View {
        List {
            if model.knownAddresses.isEmpty {
                Text(String(localized: "settings.known.empty",
                            defaultValue: "You haven’t told us you know any addresses.", bundle: .module))
                    .foregroundStyle(Tokens.muted)
            } else {
                Section {
                    ForEach(model.knownAddresses.entries, id: \.domain) { entry in
                        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                            Text(entry.domain).textRole(.body)
                            Text(entry.added.formatted(date: .abbreviated, time: .omitted))
                                .textRole(.caption)
                        }
                        .accessibilityElement(children: .combine)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                Task { await model.forgetAddress(entry.domain) }
                            } label: {
                                Text(String(localized: "settings.known.remove", defaultValue: "Remove", bundle: .module))
                            }
                        }
                        .accessibilityAction(named: Text(String(localized: "settings.known.remove",
                                                                defaultValue: "Remove", bundle: .module))) {
                            Task { await model.forgetAddress(entry.domain) }
                        }
                    }
                }
                Section {
                    Button(role: .destructive) {
                        confirmForgetAll = true
                    } label: {
                        Text(String(localized: "settings.known.forgetAll", defaultValue: "Forget all", bundle: .module))
                    }
                    .accessibilityIdentifier(QRAccessibilityID.knownForgetAll)
                }
            }
        }
        .sevenScreenBackground()
        .accessibilityIdentifier(QRAccessibilityID.knownList)
        .navigationTitle(String(localized: "settings.known.row", defaultValue: "Addresses you know", bundle: .module))
        .confirmationDialog(
            String(localized: "settings.known.forgetAll.title", defaultValue: "Forget all addresses?", bundle: .module),
            isPresented: $confirmForgetAll, titleVisibility: .visible
        ) {
            Button(String(localized: "settings.known.forgetAll.confirm", defaultValue: "Forget all", bundle: .module),
                   role: .destructive) { Task { await model.forgetAllAddresses() } }
            Button(String(localized: "history.cancel", defaultValue: "Cancel", bundle: .module), role: .cancel) {}
        } message: {
            Text(String(localized: "settings.known.forgetAll.message",
                        defaultValue: "These addresses will get their warnings again. It can’t be undone.", bundle: .module))
        }
    }
}
