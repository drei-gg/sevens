import SwiftUI
import AlarmCore

struct CustomNapSheet: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var hours = 0
    @State private var minutes = 25
    @State private var saving = false
    @State private var saveError: String?
    private var duration: Int { hours * 60 + minutes }
    var body: some View {
        NavigationStack {
            Form {
                Section("Nap length") {
                    Picker("Hours",selection:$hours) { ForEach(0...12,id:\.self) { value in Text("\(value) h").tag(value) } }
                    Picker("Minutes",selection:$minutes) { ForEach(0...59,id:\.self) { value in Text("\(value) min").tag(value) } }
                    if (1...720).contains(duration) {
                        TimelineView(.periodic(from:.now,by:60)) { context in
                            LabeledContent("Rings at",value:context.date.addingTimeInterval(Double(duration)*60).formatted(date:.omitted,time:.shortened))
                        }
                    } else { Text("Choose between 1 minute and 12 hours.").foregroundStyle(.secondary) }
                }
                Section {
                    Text("Uses your default sound, snooze and wake-up check settings. You can edit the nap after starting it.").font(.footnote).foregroundStyle(.secondary)
                    if let saveError { Text(saveError).foregroundStyle(.red) }
                }
            }.navigationTitle("Custom nap")
                .toolbar {
                    ToolbarItem(placement:.cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement:.confirmationAction) {
                        Button("Start nap") {
                            saving = true
                            Task {
                                if await model.nap(minutes:duration) { dismiss() }
                                else { saveError = model.error; model.error = nil }
                                saving = false
                            }
                        }.disabled(!(1...720).contains(duration) || saving).accessibilityIdentifier("start-custom-nap")
                    }
                }
        }
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
                AlarmTonePicker(tone:$defaults.tone)
                Stepper("Snooze: \(defaults.snoozeMinutes) minutes",value:$defaults.snoozeMinutes,in:1...30)
                Stepper("Snooze limit: \(defaults.snoozeLimit)",value:$defaults.snoozeLimit,in:0...10)
                Stepper(defaults.followUpMinutes == 0 ? "Wake-up check: off" : "Check again after \(defaults.followUpMinutes) minutes",value:$defaults.followUpMinutes,in:0...30)
            } footer: {
                Text("Applies to new alarms and naps. Existing routines and quiet reminders keep their settings. Duplicates keep the original's settings.")
            }
            Section { Button("Reset defaults") { defaults = AlarmDefaults() } }
        }.navigationTitle("New alarm defaults")
            .toolbar { Button("Save") { saving = true; Task { if await model.saveDefaults(defaults) { dismiss() }; saving = false } }.disabled(saving).accessibilityIdentifier("save-alarm-defaults") }
    }
}

struct AlarmTonePicker: View {
    @Binding var tone: AlarmTone
    var body: some View {
        Picker("Tone",selection:$tone) {
            Text("System default").tag(AlarmTone.system)
            Text("Soft chime").tag(AlarmTone.chime)
            Text("Pulse").tag(AlarmTone.pulse)
            Text("Bright bells").tag(AlarmTone.bright)
        }
    }
}

struct NextOccurrenceLabel: View {
    let routine: Routine
    var body: some View {
        TimelineView(.periodic(from:.now,by:60)) { context in
            if !routine.enabled { Text("Off") }
            else if let next = try? ScheduleCompiler.occurrences(routine,after:context.date).first {
                Text("\(remaining(until:next,from:context.date)) · \(next.formatted(date:.abbreviated,time:.shortened))")
            } else if routine.timing == .once { Text("Time has passed") }
        }.font(.caption).foregroundStyle(.secondary)
    }
    private func remaining(until date: Date, from now: Date) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.day,.hour,.minute]; formatter.unitsStyle = .abbreviated
        let interval = date.timeIntervalSince(now)
        return interval < 60 ? "Less than a minute" : "In \(formatter.string(from:interval) ?? "a moment")"
    }
}
