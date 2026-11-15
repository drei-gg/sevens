import SwiftUI
import QRCore
import SevenKit

/// Wi-Fi: name, security, whether a password is present. The password shows only on a tap.
struct ResultWiFiView: View {
    let info: WiFiInfo
    let plan: WiFiJoinPlan
    @State private var revealed = false

    var securityText: String {
        if info.isOpen { return String(localized: "result.wifi.open", defaultValue: "Open, no password", bundle: .module) }
        if info.isEnterprise {
            return String(localized: "result.wifi.enterprise", defaultValue: "Company or school network (\(info.security ?? info.eapMethod ?? "EAP"))", bundle: .module)
        }
        return info.security ?? ""
    }

    var body: some View {
        ResultCard {
            Text(info.ssid.isEmpty ? String(localized: "result.wifi.noName", defaultValue: "No network name", bundle: .module) : info.ssid)
                .textRole(.title).foregroundStyle(.primary)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            ResultField(label: String(localized: "result.wifi.security", defaultValue: "Security", bundle: .module), value: securityText)
            if info.isHidden {
                Text(String(localized: "result.wifi.hidden", defaultValue: "Hidden network", bundle: .module))
                    .textRole(.caption)
            }
            if !info.isOpen {
                passwordRow
            }
            notice
        }
    }

    @ViewBuilder private var passwordRow: some View {
        if ResultActionPlan.hasPassword(info), let password = info.password {
            VStack(alignment: .leading, spacing: Tokens.Space.s) {
                Text(String(localized: "result.wifi.password", defaultValue: "Password", bundle: .module))
                    .textRole(.caption)
                if revealed {
                    Text(password).textRole(.body).monospaced().foregroundStyle(.primary)
                        .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                } else {
                    Text(String(localized: "result.wifi.hasPassword", defaultValue: "The code includes a password", bundle: .module))
                        .textRole(.body).foregroundStyle(.primary)
                }
                Button {
                    revealed.toggle()
                } label: {
                    Text(revealed
                         ? String(localized: "result.wifi.hide", defaultValue: "Hide password", bundle: .module)
                         : String(localized: "result.wifi.show", defaultValue: "Show password", bundle: .module))
                }
                .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            ResultField(label: String(localized: "result.wifi.password", defaultValue: "Password", bundle: .module),
                        value: String(localized: "result.wifi.noPassword", defaultValue: "The code doesn’t include one", bundle: .module))
        }
    }

    @ViewBuilder private var notice: some View {
        switch plan {
        case .settingsOnly:
            note(String(localized: "result.wifi.settingsOnly", defaultValue: "Join this one in Settings.", bundle: .module))
        case .copyAndSettings:
            note(String(localized: "result.wifi.settings", defaultValue: "Join in Settings › Wi-Fi.", bundle: .module))
        case .cannotJoin:
            note(String(localized: "result.wifi.cannotJoin", defaultValue: "The code has no password for this network. Join in Settings › Wi-Fi.", bundle: .module))
        case .join:
            EmptyView()
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).textRole(.caption).fixedSize(horizontal: false, vertical: true)
    }
}
