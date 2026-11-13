import SwiftUI
import SevenKit
import FastCore

struct WaterSettingsScreen: View {
    @Environment(FastModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var water = WaterSettings()
    @State private var units = TrackerSettings()
    @State private var hasGoal = false
    @State private var goal = ""
    @State private var glass = ""
    @State private var loaded = false
    @State private var saving = false
    var body: some View {
        Form {
            SaveFeedback()
            Section(f("Your daily goal")) {
                Toggle(f("Use a personal goal"), isOn: $hasGoal).accessibilityIdentifier("water.goalEnabled")
                if hasGoal {
                    TextField(f("Daily amount"), text: $goal).keyboardType(.decimalPad).accessibilityIdentifier("water.goalAmount")
                }
                Text(f("An optional target you choose, not a recommended intake. No streaks or pressure to catch up.")).textRole(.caption)
            }
            Section(f("Quick-add glass")) {
                TextField(f("Amount"), text: $glass).keyboardType(.decimalPad).accessibilityIdentifier("water.glassAmount")
                Text(f("Amounts use") + " " + FastFormat.unit(.water, settings: units) + ". " + f("Change units in Settings. Watch quick-add remains 250 mL."))
                    .textRole(.caption)
            }
            Section(f("Water reminders")) {
                Toggle(f("Remind me to drink water"), isOn: $water.remindersEnabled).accessibilityIdentifier("water.remindersEnabled")
                if water.remindersEnabled {
                    Toggle(f("Pause water reminders"), isOn: $water.remindersPaused)
                    if water.remindersPaused { Text(f("Paused until you resume.")).textRole(.caption) }
                    Picker(f("From"), selection: $water.startHour) { ForEach(0..<24, id: \.self) { Text(hour($0)).tag($0) } }
                    Picker(f("Until"), selection: $water.endHour) { ForEach(1...24, id: \.self) { Text(hour($0)).tag($0) } }
                    Picker(f("Every"), selection: $water.intervalHours) { ForEach(1...4, id: \.self) { interval in Text(interval == 1 ? f("1 hour") : String(interval) + " " + f("hours")).tag(interval) } }
                    if water.startHour >= water.endHour { Label { Text(f("End time must be after start time.")) } icon: { Image(systemName: "exclamationmark.circle").foregroundStyle(Tokens.danger) }.textRole(.caption) }
                }
                Text(f("Daily at your chosen hours, from the start time up to but not including the end. Outside this window, water reminders are quiet. They follow local time and continue whether or not a fast is running or a goal is reached."))
                    .textRole(.caption)
                Text(model.notificationStatus).textRole(.caption)
                Button(f("Open iPhone notification settings")) {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
        }.sevenScreenBackground().navigationTitle(f("Water settings"))
            .onAppear {
                guard !loaded else { return }; loaded = true
                units = model.archive.settings; water = units.water
                hasGoal = water.goalMilliliters != nil
                goal = water.goalMilliliters.map(display) ?? ""
                glass = display(water.quickAddMilliliters)
            }
            .toolbar { Button(f("Save")) { Task { await save() } }.disabled(candidate == nil || saving || model.busy).accessibilityIdentifier("water.saveSettings") }
    }
    private func display(_ value: Double) -> String {
        Units.display(value, kind: .water, settings: units).formatted(.number.precision(.fractionLength(0...3)).grouping(.never))
    }
    private func canonical(_ text: String) -> Double? {
        let formatter = NumberFormatter(); formatter.numberStyle = .decimal
        guard let value = formatter.number(from: text)?.doubleValue else { return nil }
        let result = Units.canonical(value, kind: .water, settings: units)
        return result.isFinite && MetricKind.water.validRange.contains(result) ? result : nil
    }
    private var candidate: WaterSettings? {
        guard let glassValue = canonical(glass), !hasGoal || canonical(goal) != nil else { return nil }
        var result = water
        result.quickAddMilliliters = glass == display(water.quickAddMilliliters) ? water.quickAddMilliliters : glassValue
        result.goalMilliliters = hasGoal ? (water.goalMilliliters.map(display) == goal ? water.goalMilliliters : canonical(goal)) : nil
        return (try? result.validate()) != nil ? result : nil
    }
    private func hour(_ hour: Int) -> String {
        if hour == 24 { return f("Midnight") }
        return Calendar.current.date(bySettingHour: hour, minute: 0, second: 0, of: Date())?.formatted(date: .omitted, time: .shortened) ?? "\(hour):00"
    }
    private func save() async {
        guard let candidate else { return }
        saving = true; defer { saving = false }
        // Save first; a failed write must not prompt for a feature that was never enabled.
        guard await model.change({ $0.settings.water = candidate }) else { return }
        if candidate.remindersEnabled && !candidate.remindersPaused { _ = await model.reminders.requestPermission(); await model.updateServices() }
        dismiss()
    }
}
