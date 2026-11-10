import SwiftUI
import SevenKitCore

/// Settings rows that come from SevenKit (SPEC §11): the trial status line, Unlock, Restore,
/// and an optional "Before you buy" row. Drop it into a `Form` or `List`:
///
///     Form { AboutSection(onShowBeforeYouBuy: { showBYB = true }); … }
///
/// Needs `.sevenStore(_:unlock:)` above it. The other building blocks (`AboutVersionRow`,
/// `AboutWhatsNewRow`, `AboutSupportRow`, `AboutLicenseRow`, `AboutNoticesRow`) take their
/// content from the app.
public struct AboutSection: View {
    @Environment(Store.self) private var store
    @State private var showUnlock = false
    private let onShowBeforeYouBuy: (() -> Void)?

    public init(onShowBeforeYouBuy: (() -> Void)? = nil) {
        self.onShowBeforeYouBuy = onShowBeforeYouBuy
    }

    public var body: some View {
        Section {
            Text(AboutStatus.line(for: store.state, day: store.trialDay, totalDays: store.trialDaysTotal))
                .foregroundStyle(Tokens.ink)
            if store.state != .unlocked {
                Button(store.unlockButtonTitle) { showUnlock = true }
                    .accessibilityLabel(store.unlockButtonTitle)
                Button(seven("seven.restore", "Restore purchases")) { Task { await store.restore() } }
                    .disabled(store.isBusy)
                    .accessibilityLabel(seven("seven.restore", "Restore purchases"))
            }
            if let error = store.lastError {
                Text(error).font(.callout).foregroundStyle(Tokens.muted)
            }
            if let onShowBeforeYouBuy {
                Button(seven("seven.about.beforeYouBuy", "Before you buy"), action: onShowBeforeYouBuy)
                    .accessibilityLabel(seven("seven.about.beforeYouBuy", "Before you buy"))
            }
        } header: {
            Text(seven("seven.about.header", "Trial and unlock"))
                .foregroundStyle(Tokens.muted)
        }
        .unlockSheet(isPresented: $showUnlock)
    }
}

/// The trial status sentence. Pure, so tests can check it.
enum AboutStatus {
    static func line(for state: EntitlementState, day: Int?, totalDays: Int) -> String {
        switch state {
        case .notStarted:
            return seven("seven.about.status.notStarted", "Free trial not started. Scanning stays free.")
        case .trial(let end):
            let date = end.formatted(date: .abbreviated, time: .omitted)
            if let day {
                return String(format: seven("seven.about.status.trial", "Free trial: day %1$d of %2$d, ends %3$@."),
                              day, totalDays, date)
            }
            return seven("seven.about.status.trialNoDay", "Free trial, ends %@.", date)
        case .expired:
            return seven("seven.about.status.expired", "Free trial ended.")
        case .unlocked:
            return seven("seven.about.status.unlocked", "Unlocked. Thank you.")
        case .pending:
            return seven("seven.about.status.pending", "Waiting for approval of your purchase.")
        }
    }
}

// MARK: Building blocks

public struct AboutVersionRow: View {
    private let version: String
    private let build: String

    /// Defaults to the main bundle's version and build.
    public init(version: String? = nil, build: String? = nil) {
        let info = Bundle.main.infoDictionary
        self.version = version ?? (info?["CFBundleShortVersionString"] as? String) ?? "-"
        self.build = build ?? (info?["CFBundleVersion"] as? String) ?? "-"
    }

    public var body: some View {
        LabeledContent(seven("seven.about.version", "Version")) {
            Text("\(version) (\(build))").foregroundStyle(Tokens.muted)
        }
        .accessibilityElement(children: .combine)
    }
}

/// "What's new": the app passes the changelog text.
public struct AboutWhatsNewRow: View {
    private let text: String
    public init(text: String) { self.text = text }

    public var body: some View {
        NavigationLink(seven("seven.about.whatsNew", "What's new")) { AboutTextScreen(title: seven("seven.about.whatsNew", "What's new"), text: text) }
    }
}

public struct AboutSupportRow: View {
    private let address: String
    private let subject: String?
    public init(address: String, subject: String? = nil) {
        self.address = address
        self.subject = subject
    }

    public var body: some View {
        if let url = AboutLinks.mailto(address: address, subject: subject) {
            Link(destination: url) {
                LabeledContent(seven("seven.about.support", "Support")) {
                    Text(address).foregroundStyle(Tokens.muted)
                }
            }
            .accessibilityLabel(seven("seven.about.supportEmail", "Email support"))
        }
    }
}

/// License: GPL-3.0 with a link to the source.
public struct AboutLicenseRow: View {
    private let repositoryURL: URL
    private let license: String
    public init(repositoryURL: URL, license: String = "GPL-3.0") {
        self.repositoryURL = repositoryURL
        self.license = license
    }

    public var body: some View {
        Link(destination: repositoryURL) {
            LabeledContent(seven("seven.about.license", "License")) {
                Text(license).foregroundStyle(Tokens.muted)
            }
        }
        .accessibilityLabel(seven("seven.about.licenseLabel", "License and source code"))
    }
}

/// Notices (third-party data, trademark lines), one paragraph each.
public struct AboutNoticesRow: View {
    private let notices: [String]
    public init(notices: [String]) { self.notices = notices }

    public var body: some View {
        NavigationLink(seven("seven.about.notices", "Notices")) {
            AboutTextScreen(title: seven("seven.about.notices", "Notices"), text: notices.joined(separator: "\n\n"))
        }
    }
}

struct AboutTextScreen: View {
    let title: String
    let text: String
    var body: some View {
        ScrollView {
            Text(text).font(.body).foregroundStyle(Tokens.ink).textSelection(.enabled)
                .padding(Tokens.Space.m).frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Tokens.paper.ignoresSafeArea())
        .navigationTitle(title)
    }
}

public enum AboutLinks {
    /// `mailto:` for a support address; nil when the address is empty or has no "@".
    public static func mailto(address: String, subject: String? = nil) -> URL? {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.contains("@") else { return nil }
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = trimmed
        if let subject, !subject.isEmpty { components.queryItems = [URLQueryItem(name: "subject", value: subject)] }
        return components.url
    }
}

private struct AboutPreviewList: View {
    var body: some View {
        NavigationStack {
            Form {
                AboutSection(onShowBeforeYouBuy: {})
                Section {
                    AboutVersionRow(version: "1.0", build: "1")
                    AboutWhatsNewRow(text: "First release.")
                    AboutSupportRow(address: "support@example.org")
                    AboutLicenseRow(repositoryURL: URL(string: "https://example.org/repo") ?? URL(fileURLWithPath: "/"))
                    AboutNoticesRow(notices: ["Public Suffix List, MPL-2.0."])
                }
            }
        }
    }
}

#Preview("About, light") {
    AboutPreviewList().sevenStore(SevenPreviewSupport.store(.trial), unlock: .preview)
}
#Preview("About, dark") {
    AboutPreviewList().sevenStore(SevenPreviewSupport.store(.expired), unlock: .preview)
        .preferredColorScheme(.dark)
}
#Preview("About, largest text") {
    AboutPreviewList().sevenStore(SevenPreviewSupport.store(.fresh), unlock: .preview)
        .environment(\.dynamicTypeSize, .accessibility5)
}
