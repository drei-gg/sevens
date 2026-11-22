import SwiftUI
import UIKit
import MessageUI
import EventKit
import QRCore

/// The state behind one result's actions: what is presented, what is awaiting confirmation,
/// what the last tap said. Every change starts from a tap (SPEC §3).
@Observable @MainActor
final class ResultActionCenter {
    struct IdentifiedURL: Identifiable, Equatable {
        let url: URL
        var id: String { url.absoluteString }
    }

    struct OpenRequest: Equatable {
        let url: URL
        let domain: String
        /// true = hand to Safari; false = in-app Safari view.
        let external: Bool
    }

    enum Sheet: Identifiable {
        case contact(ContactInfo), event(EventInfo), message(number: String, body: String?)
        var id: String {
            switch self {
            case .contact: return "contact"
            case .event: return "event"
            case .message: return "message"
            }
        }
    }

    let analysis: Analysis
    let capabilities: ResultCapabilities

    var safari: IdentifiedURL?
    var sheet: Sheet?
    var pendingOpen: OpenRequest?
    /// The Peek sheet's model while the sheet is up.
    var peekModel: PeekModel?
    /// This result's one Peek (PeekModel is one per result), kept after its sheet closes so the
    /// verdict, the reasons and Open's confirmation keep what it found.
    private(set) var peek: PeekModel?
    /// The action whose button reads "Copied" for a moment.
    var copied: ResultAction?
    /// One line of feedback (Wi-Fi join result).
    var notice: String?

    init(analysis: Analysis, capabilities: ResultCapabilities) {
        self.analysis = analysis
        self.capabilities = capabilities
    }

    // MARK: Verdict

    /// The finished Peek's report, or nil.
    var peekReport: PeekReport? {
        if case .finished(let report) = peek?.state { return report }
        return nil
    }

    /// The verdict shown and acted on: the offline level raised by a finished Peek.
    var level: Level { ResultActionPlan.effectiveLevel(analysis.level, peek: peekReport) }

    // MARK: Open

    /// Open or Open in Safari. A result that is `likelyScam`, offline or after Peek, asks first.
    func openTapped(external: Bool, openURL: OpenURLAction) {
        guard case .link(let link) = analysis.payload, let url = ResultActionURLs.open(link) else { return }
        let request = OpenRequest(url: url, domain: ResultLinkDisplay.headline(link), external: external)
        if ResultActionPlan.needsOpenConfirmation(offline: analysis.level, peek: peekReport) {
            pendingOpen = request
        } else {
            perform(request, openURL: openURL)
        }
    }

    func perform(_ request: OpenRequest, openURL: OpenURLAction) {
        pendingOpen = nil
        if request.external { openURL(request.url) } else { safari = IdentifiedURL(url: request.url) }
    }

    // MARK: Peek

    /// Shows the Peek sheet. A finished Peek is shown again rather than fetched again; a
    /// cancelled one starts afresh when the sheet appears.
    func startPeek() {
        guard analysis.canPeek else { return }
        let model = peek ?? PeekModel(original: analysis)
        peek = model
        peekModel = model
    }

    /// The sheet closed: an unfinished Peek stops; a finished one stays with this result.
    func endPeek() {
        if peekModel?.isRunning == true { peekModel?.cancel() }
        peekModel = nil
    }

    // MARK: Everything else

    func perform(_ action: ResultAction, openURL: OpenURLAction) {
        switch action {
        case .open: openTapped(external: false, openURL: openURL)
        case .peek: startPeek()
        case .makeCode, .share: break   // a NavigationLink and a ShareLink in the action bar
        case .copy, .copyDetails: copy(for: action)
        case .copyPassword: copy(for: action)
        case .joinWiFi:
            if case .wifi(let info) = analysis.payload { Task { await join(info) } }
        case .addContact:
            if case .contact(let info) = analysis.payload { sheet = .contact(info) }
        case .addEvent:
            if case .event(let info) = analysis.payload { sheet = .event(info) }
        case .call:
            if case .phone(let info) = analysis.payload, let url = ResultActionURLs.call(info.number) { openURL(url) }
        case .mail:
            if case .email(let info) = analysis.payload, let url = ResultActionURLs.mail(info) { openURL(url) }
        case .maps:
            if case .geo(let info) = analysis.payload, let url = ResultActionURLs.maps(info) { openURL(url) }
        case .message:
            if case .sms(let info) = analysis.payload { message(info, openURL: openURL) }
        case .openPaymentIn:
            if let url = ResultActionURLs.openPayment(analysis) { openURL(url) }
        }
    }

    private func message(_ info: SMSInfo, openURL: OpenURLAction) {
        if capabilities.canComposeMessage {
            sheet = .message(number: info.number, body: info.body)
        } else if let url = ResultActionURLs.sms(info) {
            openURL(url)
        }
    }

    private func copy(for action: ResultAction) {
        let text: String?
        if action == .copyPassword, case .wifi(let info) = analysis.payload {
            text = info.password
        } else {
            text = ResultPayloadText.copyText(for: analysis)
        }
        guard let text, !text.isEmpty else { return }
        UIPasteboard.general.string = text
        copied = action
        AccessibilityNotification.Announcement(String(localized: "result.copied", defaultValue: "Copied", bundle: .module)).post()
        Task {
            try? await Task.sleep(for: .seconds(2))
            if copied == action { copied = nil }
        }
    }

    private func join(_ info: WiFiInfo) async {
        notice = String(localized: "result.wifi.joining", defaultValue: "Joining…", bundle: .module)
        switch await ResultWiFiJoiner.join(info) {
        case .joined:
            notice = String(localized: "result.wifi.joined", defaultValue: "Joined \(info.ssid).", bundle: .module)
        case .alreadyJoined:
            notice = String(localized: "result.wifi.already", defaultValue: "You’re already on \(info.ssid).", bundle: .module)
        case .declined:
            notice = String(localized: "result.wifi.declined", defaultValue: "Not joined.", bundle: .module)
        case .failed:
            notice = String(localized: "result.wifi.failed", defaultValue: "Couldn’t join. Try Settings › Wi-Fi.", bundle: .module)
        }
    }
}

extension ResultCapabilities {
    /// The real device and build.
    @MainActor static func current(bundle: Bundle = .main) -> ResultCapabilities {
        ResultCapabilities(
            wifiJoinAvailable: wifiFlag(in: bundle),
            canAddEvent: EKEventStore.authorizationStatus(for: .event) != .restricted,
            canComposeMessage: MFMessageComposeViewController.canSendText())
    }
}
