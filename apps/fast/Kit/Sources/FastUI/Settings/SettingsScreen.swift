import SwiftUI
import SevenKit
import FastCore

struct SettingsScreen: View {
    @Environment(FastModel.self) private var model
    @State private var before = false
    var body: some View {
        Form {
            Section(f("Make it yours")) {
                Toggle(f("Show weight"), isOn: setting(\.showWeight))
                Toggle(f("Show eating window"), isOn: setting(\.showEatingWindow))
                Toggle(f("Show Live Activity on Lock Screen"), isOn: setting(\.liveActivity))
                Text(f("iOS limits each Live Activity to eight hours. Your fast keeps running; reopen the app to refresh the Lock Screen timer. The Home Screen widget remains available.")).font(.footnote).foregroundStyle(.secondary)
                Toggle(f("Weight in pounds"), isOn: setting(\.usePounds))
                Toggle(f("Water in US fluid ounces"), isOn: setting(\.useFluidOunces))
                NavigationLink(f("Reminders")) { ReminderSettingsScreen() }
                NavigationLink(f("Plans & presets")) { PlansScreen() }
            }
            Section(f("Your data")) {
                NavigationLink(f("Export, import & delete")) { BackupScreen() }
                NavigationLink(f("Apple Health")) { HealthImportScreen() }
                Text(f("Everything is stored on this phone. No account, ads or analytics. Your device backup may include these records.")).font(.footnote).foregroundStyle(.secondary)
            }
            AboutSection(onShowBeforeYouBuy: { before = true }, notStartedStatus: f("Free trial not started. Your saved records stay accessible."))
            Section(f("About")) {
                AboutVersionRow()
                NavigationLink(f("About fasting & this tracker")) { HealthNoteScreen() }
                NavigationLink(f("Network contact")) {
                    List {
                        Text(f("Apple handles purchases and restores through StoreKit. Tracking, journal entries, reminders and measurements stay on this device."))
                        Text(f("Exports go only to the destination you choose. About links open only when tapped."))
                    }.navigationTitle(f("Network contact"))
                }
                AboutWhatsNewRow(text: f("First development build: editable fasting records, calendar and heatmap, journal, measurements, plans, reminders and portable backups."))
                AboutLicenseRow(repositoryURL: URL(string: "https://github.com/drei-gg/sevens")!)
                AboutNoticesRow(notices: [f("Built with Apple's frameworks and the shared SevenKit package. No third-party libraries or tracking SDKs.")])
                if let email = Bundle.main.object(forInfoDictionaryKey: "SevensSupportEmail") as? String,
                   !email.contains("example.org"), !email.contains("$(") { AboutSupportRow(address: email) }
            }
        }
        .disabled(model.busy)
        .navigationTitle(f("Settings"))
        .sheet(isPresented: $before) { BeforeYouBuyView(content: FastGate.before) { _ in before = false } }
    }
    private func setting(_ key: WritableKeyPath<TrackerSettings, Bool>) -> Binding<Bool> {
        Binding(get: { model.archive.settings[keyPath: key] }, set: { value in
            var settings = model.archive.settings; settings[keyPath: key] = value
            let updated = settings
            Task { _ = await model.change { $0.settings = updated } }
        })
    }
}

struct HealthNoteScreen: View {
    var body: some View {
        List {
            Section(f("A record, not a prescription")) {
                Text(f("Fasting is not appropriate for everyone. People under 18, people who are pregnant or breastfeeding, and people with a history of eating disorders should not use this app to pursue fasting. If you have a medical condition or take medication, discuss fasting with a qualified clinician first."))
                Text(f("Longer fasts are not necessarily better. End a fast if you feel unwell and seek medical help when needed. An early finish is a valid record."))
            }
            Section(f("What the timer knows")) {
                Text(f("Only the times you record. It cannot measure ketosis, autophagy, fat burning or your health. The preset durations are timer conveniences, not personalized recommendations."))
                Text(f("Charts describe your records. They do not diagnose conditions or prove that fasting caused changes in your weight, mood or energy."))
            }
            Section(f("Read the source")) {
                Link(f("Johns Hopkins: Intermittent fasting"), destination: URL(string: "https://www.hopkinsmedicine.org/health/expert-qa/intermittent-fasting-what-is-it-and-how-does-it-work")!)
            }
        }.navigationTitle(f("About fasting"))
    }
}
