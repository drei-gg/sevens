import SwiftUI
import QRCore
import SevenKit

/// Route values for the result's NavigationStack.
enum ResultRoute: Hashable {
    case embedded(Analysis)
    case make(MakeRequest)
}

/// How one action button is drawn.
enum ResultActionRole {
    /// The one filled, extra-large capsule pinned at the bottom.
    case filled
    /// Bordered, full width, under the filled one.
    case outlined
    /// A one-tap capsule chip in the flow of the sheet.
    case chip
    /// Bordered, full width, in the flow (accessibility sizes, where nothing is pinned).
    case stacked
}

/// The pinned actions at the bottom of a result: one prominent, one bordered, stacked
/// (polish guide §5.1). `quiet` draws the primary bordered too, when the sheet already shows a
/// filled button of its own ("Keep warning me"), so no screen has two ink fills.
struct ResultPinnedActions: View {
    let layout: ResultActionLayout
    let analysis: Analysis
    let center: ResultActionCenter
    var quiet = false

    var body: some View {
        if layout.primary != nil || layout.secondary != nil {
            VStack(spacing: Tokens.Space.s) {
                if let primary = layout.primary {
                    ResultActionButton(action: primary, role: quiet ? .outlined : .filled, analysis: analysis, center: center)
                }
                if let secondary = layout.secondary {
                    ResultActionButton(action: secondary, role: .outlined, analysis: analysis, center: center)
                }
            }
            .padding(.horizontal, Tokens.Space.m)
            .padding(.top, Tokens.Space.ms)
            .padding(.bottom, Tokens.Space.s)
            .frame(maxWidth: .infinity)
            .background(.bar)
        }
    }
}

/// The secondary actions as a wrapping row of chips.
struct ResultActionChips: View {
    let actions: [ResultAction]
    let analysis: Analysis
    let center: ResultActionCenter
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        if !actions.isEmpty {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Tokens.Space.s) { chips }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 240 : 140), spacing: Tokens.Space.s)],
                          alignment: .leading, spacing: Tokens.Space.s) { chips }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var chips: some View {
        ForEach(actions, id: \.self) { action in
            ResultActionButton(action: action, role: .chip, analysis: analysis, center: center)
        }
    }
}

/// Every action stacked full width, in the flow of the sheet: the layout at accessibility
/// sizes, where a pinned pair would cover half the screen.
struct ResultActionBar: View {
    let actions: [ResultAction]
    let analysis: Analysis
    let center: ResultActionCenter

    var body: some View {
        VStack(spacing: Tokens.Space.s) {
            ForEach(actions, id: \.self) { action in
                ResultActionButton(action: action, role: .stacked, analysis: analysis, center: center)
            }
        }
    }
}

/// One action. Each button is a tap; nothing runs by itself.
struct ResultActionButton: View {
    let action: ResultAction
    let role: ResultActionRole
    let analysis: Analysis
    let center: ResultActionCenter
    @Environment(\.openURL) private var openURL

    var body: some View {
        switch role {
        case .filled:
            control.sevenProminentButton().controlSize(.extraLarge).buttonBorderShape(.capsule)
        case .outlined:
            control.modifier(SevenButtonStyle(kind: .bordered)).controlSize(.large).buttonBorderShape(.capsule)
        case .chip:
            control.quickAddChip()
        case .stacked:
            control.modifier(SevenButtonStyle(kind: .bordered)).controlSize(.large)
        }
    }

    @ViewBuilder private var control: some View {
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
                label()
            } primaryAction: {
                center.openTapped(external: false, openURL: openURL)
            }
            .accessibilityIdentifier(QRAccessibilityID.resultOpen)
        case .peek:
            GatedButton(feature: String(localized: "result.feature.peek", defaultValue: "Peek", bundle: .module)) {
                center.startPeek()
            } label: {
                label()
            }
            .accessibilityIdentifier(QRAccessibilityID.resultPeek)
        case .share:
            if let text = ResultPayloadText.shareText(for: analysis) {
                ShareLink(item: text) { label() }
            }
        case .makeCode:
            if let request = ResultMakeMapping.request(for: analysis) {
                NavigationLink(value: ResultRoute.make(request)) { label() }
            }
        default:
            Button {
                center.perform(action, openURL: openURL)
            } label: {
                label(copied: center.copied == action)
            }
            .sensoryFeedback(.success, trigger: center.copied == action) { _, copied in copied }
        }
    }

    @ViewBuilder private func label(copied: Bool = false) -> some View {
        let title = copied ? String(localized: "result.copied", defaultValue: "Copied", bundle: .module) : ResultActionBar.title(action)
        let symbol = copied ? "checkmark" : ResultActionBar.symbol(action)
        if role == .chip {
            ChipLabel(title: title, systemImage: symbol)
        } else {
            Label {
                Text(title).multilineTextAlignment(.center)
            } icon: {
                Image(systemName: symbol)
            }
            .textRole(.rowTitle, emphasized: true)
            .frame(maxWidth: .infinity, minHeight: 28)
        }
    }
}

extension ResultActionBar {
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
        case .makeCode: return String(localized: "result.action.make", defaultValue: "Make a code", bundle: .module)
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
