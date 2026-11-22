import SwiftUI
import SevenKit
import AlarmCore

/// A nap of any length, set like Clock's timer: hour and minute wheels, the time it will ring
/// big beneath them, and one button to start.
struct CustomNapSheet: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var hours = 0
    @State private var minutes = 25
    @State private var saving = false
    @State private var saveError: String?
    private var duration: Int { hours * 60 + minutes }
    private var valid: Bool { (1...720).contains(duration) }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Tokens.Space.l) {
                    HStack(spacing: Tokens.Space.s) {
                        wheel(f("Hours"), unit: f("hr"), selection: $hours, range: 0...12)
                        wheel(f("Minutes"), unit: f("min"), selection: $minutes, range: 0...59)
                    }
                    .padding(Tokens.Space.s).sevenCard()
                    TimelineView(.periodic(from: .now, by: 30)) { context in
                        VStack(spacing: Tokens.Space.xs) {
                            if valid {
                                Text(f("Rings at")).textRole(.caption, emphasized: true).textCase(.uppercase)
                                ClockText(parts: ClockFormat.parts(context.date.addingTimeInterval(Double(duration) * 60)), role: .hero)
                            } else {
                                Text(f("Choose between 1 minute and 12 hours.")).textRole(.secondary)
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                    PrimaryButton(title: f("Start nap"), systemImage: "moon.zzz") {
                        saving = true
                        Task {
                            if await model.nap(minutes: duration) { dismiss() }
                            else { saveError = model.error; model.error = nil }
                            saving = false
                        }
                    }
                    .disabled(!valid || saving).accessibilityIdentifier("start-custom-nap")
                    if let saveError { Text(saveError).foregroundStyle(Tokens.danger).textRole(.secondary) }
                    Text(f("Uses your default sound, snooze and wake-up check. You can edit the nap after starting it.")).textRole(.caption).multilineTextAlignment(.center)
                }
                .padding(Tokens.Space.m).frame(maxWidth: 520).frame(maxWidth: .infinity)
            }
            .sevenScreenBackground()
            .navigationTitle(f("Custom nap")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(f("Cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func wheel(_ title: String, unit: String, selection: Binding<Int>, range: ClosedRange<Int>) -> some View {
        HStack(spacing: Tokens.Space.xs) {
            Picker(title, selection: selection) { ForEach(range, id: \.self) { value in Text(value.formatted()).numeric().tag(value) } }
                .pickerStyle(.wheel).labelsHidden()
            Text(unit).textRole(.body, emphasized: true).accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity)
        .accessibilityLabel(title)
    }
}

struct AlarmDefaultsEditor: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var defaults: AlarmDefaults
    @State private var saving = false
    var body: some View {
        Form {
            Section {
                AlarmTonePicker(tone: $defaults.tone)
                Stepper("Snooze: \(ClockFormat.minutes(defaults.snoozeMinutes))", value: $defaults.snoozeMinutes, in: 1...30)
                Stepper(defaults.snoozeLimit == 0 ? f("No snoozes") : "Snooze limit: \(defaults.snoozeLimit)", value: $defaults.snoozeLimit, in: 0...10)
                Stepper(defaults.followUpMinutes == 0 ? f("Wake-up check: off") : "Check again after \(ClockFormat.minutes(defaults.followUpMinutes))", value: $defaults.followUpMinutes, in: 0...30)
            } footer: {
                Text(f("Applies to new alarms and naps. Existing routines and quiet reminders keep their settings. Duplicates keep the original’s settings.")).textRole(.caption)
            }
            Section { Button(f("Reset defaults"), systemImage: "arrow.uturn.backward") { defaults = AlarmDefaults() } }
        }
        .sevenScreenBackground()
        .navigationTitle(f("New alarm defaults")).navigationBarTitleDisplayMode(.inline)
        .toolbar { Button(f("Save")) { saving = true; Task { if await model.saveDefaults(defaults) { dismiss() }; saving = false } }.disabled(saving).accessibilityIdentifier("save-alarm-defaults") }
    }
}

struct AlarmTonePicker: View {
    @Binding var tone: AlarmTone
    var body: some View {
        Picker(f("Tone"), selection: $tone) {
            Text(f("System default")).tag(AlarmTone.system)
            Text(f("Soft chime")).tag(AlarmTone.chime)
            Text(f("Pulse")).tag(AlarmTone.pulse)
            Text(f("Bright bells")).tag(AlarmTone.bright)
        }
    }
}

/// "Thursday, in 14 hr, 46 min", or Off, kept fresh each half minute.
struct NextOccurrenceLabel: View {
    let routine: Routine
    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            Group {
                if !routine.enabled { Text(f("Off")) }
                else if let next = routine.next(after: context.date) {
                    Text("\(ClockFormat.day(next, from: context.date)), \(ClockFormat.countdown(to: next, from: context.date))")
                } else if routine.timing == .once { Text(f("Time has passed")) }
            }
            .textRole(.caption).numeric()
        }
    }
}
