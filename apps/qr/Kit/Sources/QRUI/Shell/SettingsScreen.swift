import SwiftUI
import QRCore
import SevenKit

/// Settings and About (PLAN §6.12): one screen.
public struct SettingsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var showBeforeYouBuy = false
    @State private var confirmClear = false

    /// Settings and About (PLAN §6.12). Pushed inside a NavigationStack.
    public init() {}

    public var body: some View {
        @Bindable var settings = model.settings
        Form {
            AboutSection(onShowBeforeYouBuy: { showBeforeYouBuy = true })
            Section {
                NavigationLink(String(localized: "settings.network", defaultValue: "Network contact", bundle: .module)) {
                    NetworkContactScreen()
                }
            }
            historySection(keepHistory: $settings.keepHistory)
            aboutSection
            #if DEBUG
            SevenDebugPanel()
            #endif
        }
        .scrollContentBackground(.hidden)
        .background(Tokens.paper.ignoresSafeArea())
        .navigationTitle(String(localized: "settings.title", defaultValue: "Settings", bundle: .module))
        .sheet(isPresented: $showBeforeYouBuy) {
            BeforeYouBuyView(content: QRGate.beforeYouBuyContent()) { _ in showBeforeYouBuy = false }
        }
        .confirmationDialog(
            String(localized: "settings.clear.title", defaultValue: "Clear all history?", bundle: .module),
            isPresented: $confirmClear, titleVisibility: .visible
        ) {
            Button(String(localized: "settings.clear.confirm", defaultValue: "Clear history", bundle: .module), role: .destructive) {
                Task { await model.clearHistory() }
            }
            Button(String(localized: "history.cancel", defaultValue: "Cancel", bundle: .module), role: .cancel) {}
        } message: {
            Text(String(localized: "settings.clear.message",
                        defaultValue: "This removes every saved scan and made code, with its notes. It can't be undone.",
                        bundle: .module))
        }
    }

    private func historySection(keepHistory: Binding<Bool>) -> some View {
        Section {
            Toggle(String(localized: "settings.keepHistory", defaultValue: "Keep history", bundle: .module), isOn: keepHistory)
            Button(role: .destructive) {
                confirmClear = true
            } label: {
                Text(String(localized: "settings.clear", defaultValue: "Clear history", bundle: .module))
            }
            .disabled(model.historyIsReadOnly || model.historyCount == 0)
        } header: {
            Text(String(localized: "settings.history.header", defaultValue: "History", bundle: .module))
        } footer: {
            if model.historyIsReadOnly, let reason = model.historyReadOnlyReason {
                Text(reason)
            } else if model.historyIsLocked && keepHistory.wrappedValue {
                Text(String(localized: "settings.history.locked",
                            defaultValue: "Saving new scans needs the unlock. What is already saved stays readable.",
                            bundle: .module))
            } else {
                Text(String(localized: "settings.history.footer",
                            defaultValue: "Turning this off stops saving new codes. It doesn't delete what is already saved.",
                            bundle: .module))
            }
        }
    }

    private var aboutSection: some View {
        Section {
            AboutVersionRow()
            if let changelog = SettingsInfo.changelog() { AboutWhatsNewRow(text: changelog) }
            if let address = SettingsInfo.supportEmail() {
                AboutSupportRow(address: address,
                                subject: String(localized: "settings.support.subject",
                                                defaultValue: "QR Code Kit support", bundle: .module))
            }
            if let repository = SettingsInfo.repositoryURL() {
                AboutLicenseRow(repositoryURL: repository, license: "GPL-3.0")
            }
            AboutNoticesRow(notices: SettingsNotices.paragraphs())
        } header: {
            Text(String(localized: "settings.about.header", defaultValue: "About", bundle: .module))
        }
    }
}

#Preview("Settings, light") {
    NavigationStack { SettingsScreen() }.qrStore(.preview(state: .trial))
}
#Preview("Settings, dark") {
    NavigationStack { SettingsScreen() }.qrStore(.preview(state: .expired)).preferredColorScheme(.dark)
}
#Preview("Settings, largest text") {
    NavigationStack { SettingsScreen() }.qrStore(.preview(state: .fresh))
        .environment(\.dynamicTypeSize, .accessibility5)
}
