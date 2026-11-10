import SwiftUI
import QRCore
import SevenKit

/// Route values for the result's NavigationStack.
enum ResultRoute: Hashable {
    case embedded(Analysis)
    case make(MakeRequest)
}

/// The action row. Each button is a tap; nothing runs by itself.
struct ResultActionBar: View {
    let actions: [ResultAction]
    let analysis: Analysis
    let center: ResultActionCenter
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: Tokens.Space.s) {
            ForEach(actions, id: \.self) { action in
                row(for: action)
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .tint(Tokens.ink)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder private func row(for action: ResultAction) -> some View {
        switch action {
        case .open:
            Menu {
                Button {
                    center.openTapped(external: true, openURL: openURL)
                } label: {
                    Label(String(localized: "result.action.openSafari", defaultValue: "Open in Safari", bundle: .module),
                          systemImage: "arrow.up.right.square")
                }
            } label: {
                fullWidth(action)
            } primaryAction: {
                center.openTapped(external: false, openURL: openURL)
            }
            .accessibilityIdentifier(QRAccessibilityID.resultOpen)
        case .peek:
            GatedButton(feature: String(localized: "result.feature.peek", defaultValue: "Peek", bundle: .module)) {
                center.startPeek()
            } label: {
                fullWidth(action)
            }
            .accessibilityIdentifier(QRAccessibilityID.resultPeek)
        case .share:
            if let text = ResultPayloadText.shareText(for: analysis) {
                ShareLink(item: text) { fullWidth(action) }
            }
        case .makeCode:
            if let request = ResultMakeMapping.request(for: analysis) {
                NavigationLink(value: ResultRoute.make(request)) { fullWidth(action) }
            }
        default:
            Button {
                center.perform(action, openURL: openURL)
            } label: {
                fullWidth(action, copied: center.copied == action)
            }
        }
    }

    private func fullWidth(_ action: ResultAction, copied: Bool = false) -> some View {
        Label {
            Text(copied ? String(localized: "result.copied", defaultValue: "Copied", bundle: .module) : Self.title(action))
                .multilineTextAlignment(.center)
        } icon: {
            Image(systemName: copied ? "checkmark" : Self.symbol(action))
        }
        .font(.headline)
        .frame(maxWidth: .infinity, minHeight: 28)
    }

    static func title(_ action: ResultAction) -> String {
        switch action {
        case .open: return String(localized: "result.action.open", defaultValue: "Open link", bundle: .module)
        case .peek: return String(localized: "result.action.peek", defaultValue: "Peek", bundle: .module)
        case .joinWiFi: return String(localized: "result.action.join", defaultValue: "Join Wi-Fi", bundle: .module)
        case .copyPassword: return String(localized: "result.action.copyPassword", defaultValue: "Copy password", bundle: .module)
        case .addContact: return String(localized: "result.action.addContact", defaultValue: "Add to Contacts", bundle: .module)
        case .addEvent: return String(localized: "result.action.addEvent", defaultValue: "Add to Calendar", bundle: .module)
        case .call: return String(localized: "result.action.call", defaultValue: "Call", bundle: .module)
        case .message: return String(localized: "result.action.message", defaultValue: "Message", bundle: .module)
        case .mail: return String(localized: "result.action.mail", defaultValue: "Email", bundle: .module)
        case .maps: return String(localized: "result.action.maps", defaultValue: "Open in Maps", bundle: .module)
        case .openPaymentIn: return String(localized: "result.action.openIn", defaultValue: "Open in…", bundle: .module)
        case .copy: return String(localized: "result.action.copy", defaultValue: "Copy", bundle: .module)
        case .copyDetails: return String(localized: "result.action.copyDetails", defaultValue: "Copy details", bundle: .module)
        case .share: return String(localized: "result.action.share", defaultValue: "Share", bundle: .module)
        case .makeCode: return String(localized: "result.action.make", defaultValue: "Make code", bundle: .module)
        }
    }

    static func symbol(_ action: ResultAction) -> String {
        switch action {
        case .open: return "safari"
        case .peek: return "eye"
        case .joinWiFi: return "wifi"
        case .copyPassword: return "key"
        case .addContact: return "person.crop.circle.badge.plus"
        case .addEvent: return "calendar.badge.plus"
        case .call: return "phone"
        case .message: return "message"
        case .mail: return "envelope"
        case .maps: return "map"
        case .openPaymentIn: return "arrow.up.forward.app"
        case .copy, .copyDetails: return "doc.on.doc"
        case .share: return "square.and.arrow.up"
        case .makeCode: return "qrcode"
        }
    }
}
