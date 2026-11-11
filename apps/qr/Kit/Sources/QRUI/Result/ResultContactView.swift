import SwiftUI
import QRCore
import SevenKit

/// A contact card. Links inside it are listed separately, each with its own check.
struct ResultContactView: View {
    let info: ContactInfo

    private var displayName: String {
        if let name = info.name, !name.isEmpty { return name }
        let joined = [info.givenName, info.familyName].compactMap { $0 }.joined(separator: " ")
        return joined.isEmpty ? String(localized: "result.contact.noName", defaultValue: "No name", bundle: .module) : joined
    }

    private var roleLine: String {
        [info.title, info.organization].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")
    }

    var body: some View {
        ResultCard {
            Text(displayName)
                .font(.largeTitle.weight(.bold)).foregroundStyle(.primary)
                .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if !roleLine.isEmpty {
                Text(roleLine).font(.title3).foregroundStyle(Tokens.muted).fixedSize(horizontal: false, vertical: true)
            }
            list(String(localized: "result.contact.phones", defaultValue: "Phone", bundle: .module), info.phones)
            list(String(localized: "result.contact.emails", defaultValue: "Email", bundle: .module), info.emails)
            list(String(localized: "result.contact.urls", defaultValue: "Websites", bundle: .module), info.urls)
            list(String(localized: "result.contact.addresses", defaultValue: "Address", bundle: .module), info.addresses)
            if let birthday = info.birthday, !birthday.isEmpty {
                ResultField(label: String(localized: "result.contact.birthday", defaultValue: "Birthday", bundle: .module), value: birthday)
            }
            if let note = info.note, !note.isEmpty {
                ResultField(label: String(localized: "result.contact.note", defaultValue: "Note", bundle: .module), value: note)
            }
        }
    }

    @ViewBuilder private func list(_ label: String, _ values: [String]) -> some View {
        if !values.isEmpty {
            ResultField(label: label, value: values.joined(separator: "\n"))
        }
    }
}
