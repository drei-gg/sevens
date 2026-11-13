import SwiftUI
import SevenKit
import FastCore

struct ReminderSettingsScreen: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var settings = TrackerSettings()
    @State private var time = Date()
    @State private var saving = false
    var body: some View {
        Form {
            Section(f("At the right moment")) {
                Toggle(f("Fasting goal reached"), isOn: $settings.goalReminder)
                Toggle(f("Eating window ending"), isOn: $settings.eatingReminder)
                Toggle(f("Planned fasts"), isOn: $settings.scheduleReminders)
            }
            Section { NavigationLink(f("Water reminders")) { WaterSettingsScreen() } }
            Section(f("Weekly start reminder")) {
                DatePicker(f("Time"), selection: $time, displayedComponents: .hourAndMinute)
                ForEach(1...7, id: \.self) { day in
                    Toggle(Calendar.current.weekdaySymbols[day-1], isOn: Binding(get: { settings.reminderWeekdays.contains(Int32(day)) }, set: { enabled in
                        settings.reminderWeekdays.removeAll { $0 == Int32(day) }; if enabled { settings.reminderWeekdays.append(Int32(day)) }
                    }))
                }
            }
            Section {
                Text(model.notificationStatus).foregroundStyle(Tokens.muted)
                Button(f("Open iPhone notification settings")) {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(url) }
                }
            } footer: { Text(f("Only the reminders you choose. No journal text is shown in notifications. Focus and notification settings may silence delivery.")) }
        }
        .sevenScreenBackground().navigationTitle(f("Reminders"))
        .onAppear {
            settings = model.archive.settings
            time = Calendar.current.date(bySettingHour: Int(settings.reminderHour), minute: Int(settings.reminderMinute), second: 0, of: Date())!
        }
        .toolbar { Button(f("Save")) { Task { await save() } }.disabled(saving || model.busy) }
    }
    private func save() async {
        saving = true; defer { saving = false }
        settings.reminderHour = Int32(Calendar.current.component(.hour, from: time))
        settings.reminderMinute = Int32(Calendar.current.component(.minute, from: time))
        if settings.goalReminder || settings.eatingReminder || settings.scheduleReminders || !settings.reminderWeekdays.isEmpty {
            _ = await model.reminders.requestPermission()
        }
        let value = settings
        if await model.change({
            $0.settings.goalReminder = value.goalReminder; $0.settings.eatingReminder = value.eatingReminder
            $0.settings.scheduleReminders = value.scheduleReminders; $0.settings.reminderWeekdays = value.reminderWeekdays
            $0.settings.reminderHour = value.reminderHour; $0.settings.reminderMinute = value.reminderMinute
        }) { dismiss() }
    }
}
