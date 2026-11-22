import BloomCore
import SevenKit
import SwiftUI

struct EditorFrame<Content: View>: View {
  @Environment(BloomModel.self) private var model
  @Environment(\.dismiss) private var dismiss
  let title: String
  let save: () async -> Bool
  @ViewBuilder var content: () -> Content
  @State private var saving = false
  @State private var failure: String?
  @State private var unlock = false
  var body: some View {
    NavigationStack {
      Form { content() }.navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        .sevenScreenBackground()
        .toolbar {
          ToolbarItem(placement: .cancellationAction) {
            Button("Cancel") { dismiss() }.disabled(saving)
          }
          ToolbarItem(placement: .confirmationAction) {
            Button("Save") {
              saving = true
              Task {
                if await save() {
                  dismiss()
                } else {
                  failure = model.message ?? "The change could not be saved. Please retry."
                  model.message = nil
                }
                saving = false
              }
            }.disabled(saving || model.busy).accessibilityIdentifier("saveRecord")
          }
        }
    }.interactiveDismissDisabled(saving)
      .alert(
        "Could not save",
        isPresented: Binding(get: { failure != nil }, set: { shown in if !shown { failure = nil } })
      ) {
        if failure?.contains("Start a trial") == true {
          Button("Trial and unlock") { unlock = true }
        }
        Button("OK", role: .cancel) { failure = nil }
      } message: {
        Text(failure ?? "")
      }
      .unlockSheet(isPresented: $unlock)
  }
}
struct DayEditor: View {
  @Environment(BloomModel.self) private var model
  @State var value: DayLog
  let revision: UInt64
  @State private var symptom = ""
  @State private var temperature = ""
  @State private var tags = ""
  @AppStorage("bloom.favorites") private var favorites = "Cramps,Headache,Fatigue,Calm"
  @State private var expanded = false
  private var symptoms: [String] {
    Array(Set((expanded ? bloomCategories : splitTags(favorites)) + Array(value.symptoms.keys)))
      .sorted()
  }
  var body: some View {
    EditorFrame(title: "Daily journal", save: save) {
      BloomSection("Period") {
        DatePicker(
          "Date",
          selection: Binding(
            get: { BloomDate.date(value.day) }, set: { value.day = BloomDate.day($0) }),
          in: ...Date(), displayedComponents: .date)
        Picker("Flow", selection: $value.flow) {
          choices(["none", "spotting", "light", "medium", "heavy"])
        }
        Toggle("Start of a new period", isOn: $value.period_start).onChange(of: value.period_start)
        { _, on in if on && ["none", "spotting"].contains(value.flow) { value.flow = "medium" } }
      }
      Section {
        ForEach(symptoms, id: \.self) { name in
          Picker(
            name,
            selection: Binding(
              get: { value.symptoms[name] ?? -1 },
              set: {
                if $0 < 0 {
                  value.symptoms.removeValue(forKey: name)
                } else {
                  value.symptoms[name] = $0
                }
              })
          ) {
            Text("Not logged").tag(-1)
            Text("None").tag(0)
            Text("Low").tag(1)
            Text("Medium").tag(2)
            Text("High").tag(3)
          }
        }
        Button(expanded ? "Only pinned observations" : "More observations") { expanded.toggle() }
        HStack {
          TextField("Custom observation", text: $symptom)
          Button("Add") {
            let s = symptom.trimmingCharacters(in: .whitespacesAndNewlines)
            if !s.isEmpty {
              value.symptoms[s] = 1
              symptom = ""
            }
          }.disabled(symptom.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
      } header: {
        Text("Symptoms and wellbeing")
      } footer: {
        Text(
          "Not logged and none are different. Only explicitly recorded observations enter your patterns."
        )
      }
      BloomSection("Optional observations") {
        TextField("Basal temperature, °C", text: $temperature).keyboardType(.decimalPad)
        Picker("Cervical mucus", selection: $value.mucus) {
          choices(["none", "dry", "sticky", "creamy", "watery", "egg_white"])
        }
        Picker("Ovulation test", selection: $value.ovulation_test) {
          choices(["none", "negative", "positive", "unclear"])
        }
        Picker("Pregnancy test", selection: $value.pregnancy_test) {
          choices(["none", "negative", "positive", "unclear"])
        }
      }
      BloomSection("Your notes") {
        TextField("Tags, separated by commas", text: $tags)
        TextField("Anything else you want to remember", text: $value.notes, axis: .vertical)
          .lineLimit(4...10).accessibilityIdentifier("journalNotes")
      }
    }.onAppear {
      temperature = value.bbt.map { String($0) } ?? ""
      tags = value.tags.joined(separator: ", ")
    }
  }
  func save() async -> Bool {
    let temp = temperature.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(
      of: ",", with: ".")
    if !temp.isEmpty && Double(temp) == nil {
      model.message = "Enter a valid temperature in Celsius."
      return false
    }
    value.bbt = Double(temp)
    value.tags = splitTags(tags)
    return await model.save("day", value, revision: revision)
  }
}
struct ContactEditor: View {
  @Environment(BloomModel.self) private var model
  @State var value: Contact
  let revision: UInt64
  var body: some View {
    EditorFrame(
      title: "Sex and contact", save: { await model.save("contact", value, revision: revision) }
    ) {
      Section {
        DatePicker(
          "When",
          selection: Binding(
            get: { Date(timeIntervalSince1970: Double(value.at)) },
            set: {
              value.at = Int64($0.timeIntervalSince1970)
              value.day = BloomDate.day($0)
            }), in: ...Date())
        TextField("Activity", text: $value.activity)
        TextField("Protection or contraception", text: $value.protection)
        Picker(
          "Libido",
          selection: Binding(
            get: { value.libido ?? -1 }, set: { rating in value.libido = rating < 0 ? nil : rating }
          )
        ) {
          Text("Not logged").tag(-1)
          Text("None").tag(0)
          Text("Low").tag(1)
          Text("Medium").tag(2)
          Text("High").tag(3)
        }
        Picker(
          "Pain",
          selection: Binding(
            get: { value.pain ?? -1 }, set: { rating in value.pain = rating < 0 ? nil : rating })
        ) {
          Text("Not logged").tag(-1)
          Text("None").tag(0)
          Text("Mild").tag(1)
          Text("Moderate").tag(2)
          Text("Severe").tag(3)
        }
        Picker(
          "Orgasm",
          selection: Binding(
            get: { value.orgasm.map { orgasm in orgasm ? 1 : 0 } ?? -1 },
            set: { rating in value.orgasm = rating < 0 ? nil : rating == 1 })
        ) {
          Text("Not logged").tag(-1)
          Text("No").tag(0)
          Text("Yes").tag(1)
        }
        TextField("Private notes", text: $value.notes, axis: .vertical).lineLimit(3...8)
      } footer: {
        Text(
          "These entries are excluded from reports unless you choose to include them. Family Sharing never shares your journal."
        )
      }
    }
  }
}
struct PlanEditor: View {
  @Environment(BloomModel.self) private var model
  @State var value: Plan
  let revision: UInt64
  @State private var stock = ""
  var body: some View {
    EditorFrame(title: "Reminder plan", save: save) {
      BloomSection("Your plan") {
        TextField("Plan name", text: $value.name).accessibilityIdentifier("planName")
        Picker("Method", selection: $value.method) {
          choices([
            "pill", "ring", "patch", "injection", "implant", "iud", "medication", "emergency",
          ])
        }
        Toggle("Hormonal contraception", isOn: $value.hormonal)
        Toggle("Active reminders", isOn: $value.enabled)
        DatePicker(
          "Starts",
          selection: Binding(
            get: { BloomDate.date(value.start) }, set: { value.start = BloomDate.day($0) }),
          displayedComponents: .date)
        Toggle(
          "End date",
          isOn: Binding(get: { value.end != nil }, set: { value.end = $0 ? value.start : nil }))
        if value.end != nil {
          DatePicker(
            "Ends",
            selection: Binding(
              get: { BloomDate.date(value.end ?? value.start) },
              set: { value.end = BloomDate.day($0) }), displayedComponents: .date)
        }
      }
      BloomSection("Schedule") {
        Picker("Repeat", selection: $value.pattern) {
          Text("Every day").tag("daily")
          Text("Pill pack").tag("pack")
          Text("Every N days").tag("interval")
          Text("Weekdays").tag("weekly")
          Text("Once").tag("once")
        }
        if value.pattern == "pack" {
          Stepper("Active days: \(value.active_days)", value: $value.active_days, in: 1...365)
          Stepper("Break / placebo days: \(value.break_days)", value: $value.break_days, in: 0...90)
          Toggle("Remind during break / placebo days", isOn: $value.remind_break)
        }
        if value.pattern == "interval" {
          Stepper("Every \(value.every_days) days", value: $value.every_days, in: 1...3650)
        }
        if value.pattern == "weekly" {
          ForEach(1...7, id: \.self) { day in
            Toggle(
              Calendar.current.weekdaySymbols[day - 1],
              isOn: Binding(
                get: { value.weekdays.contains(day) },
                set: { on in
                  value.weekdays.removeAll { $0 == day }
                  if on {
                    value.weekdays.append(day)
                    value.weekdays.sort()
                  }
                }))
          }
        }
        DatePicker(
          "Time",
          selection: Binding(
            get: {
              Calendar.current.startOfDay(for: Date()).addingTimeInterval(Double(value.minute * 60))
            },
            set: {
              value.minute =
                Calendar.current.component(.hour, from: $0) * 60
                + Calendar.current.component(.minute, from: $0)
            }), displayedComponents: .hourAndMinute)
        Toggle("Keep home time zone when travelling", isOn: $value.fixed_zone)
        if value.fixed_zone {
          Picker("Home time zone", selection: $value.zone) {
            ForEach(TimeZone.knownTimeZoneIdentifiers, id: \.self) { Text($0).tag($0) }
          }
        }
      }
      BloomSection("Supply and notes") {
        TextField("Starting supply (optional)", text: $stock).keyboardType(.numberPad)
        TextField("Prescription instructions or notes", text: $value.notes, axis: .vertical)
          .lineLimit(3...8)
        Text(
          "Set this schedule from your prescription or clinician’s instructions. Ring, patch and long-acting methods can use intervals or a one-time replacement reminder. This app does not decide when to change your contraception."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
    }.onAppear { stock = value.initial_stock.map(String.init) ?? "" }
  }
  func save() async -> Bool {
    if !stock.isEmpty && Int(stock) == nil {
      model.message = "Enter a whole number for starting supply."
      return false
    }
    value.initial_stock = Int(stock)
    return await model.save("plan", value, revision: revision)
  }
}
struct RefillEditor: View {
  @Environment(BloomModel.self) private var model
  let plan: Plan
  let revision: UInt64
  @State private var value: Refill
  init(plan: Plan, revision: UInt64, value: Refill? = nil) {
    self.plan = plan
    self.revision = revision
    var refill = value ?? Refill()
    refill.plan_id = plan.id
    _value = State(initialValue: refill)
  }
  var body: some View {
    EditorFrame(
      title: "Refill \(plan.name)", save: { await model.save("refill", value, revision: revision) }
    ) {
      DatePicker(
        "Date",
        selection: Binding(
          get: { BloomDate.date(value.day) }, set: { value.day = BloomDate.day($0) }),
        in: ...Date(), displayedComponents: .date)
      Stepper("Added: \(value.quantity)", value: $value.quantity, in: 1...10000)
    }
  }
}
struct RefillHistory: View {
  @Environment(BloomModel.self) private var model
  let plan: Plan
  @State private var selected: Refill?
  var body: some View {
    List {
      ForEach(model.archive.refills.filter { $0.plan_id == plan.id }.sorted { $0.day > $1.day }) {
        refill in
        Button {
          selected = refill
        } label: {
          BloomValue(prettyDay(refill.day), value: "Added \(refill.quantity)")
        }
        .swipeActions {
          Button("Delete", role: .destructive) {
            Task { await model.delete("delete_refill", id: refill.id) }
          }
        }
      }
    }.navigationTitle("Refill history").sevenScreenBackground()
      .sheet(item: $selected) {
        RefillEditor(plan: plan, revision: model.archive.revision, value: $0)
      }
  }
}
struct AppointmentEditor: View {
  @Environment(BloomModel.self) private var model
  @State var value: Appointment
  let revision: UInt64
  var body: some View {
    EditorFrame(
      title: "Appointment", save: { await model.save("appointment", value, revision: revision) }
    ) {
      TextField("Title", text: $value.title)
      DatePicker(
        "When",
        selection: Binding(
          get: { Date(timeIntervalSince1970: Double(value.at)) },
          set: { value.at = Int64($0.timeIntervalSince1970) }))
      Picker("Reminder", selection: $value.remind_minutes) {
        Text("Off").tag(-1)
        Text("At the time").tag(0)
        Text("15 minutes before").tag(15)
        Text("1 hour before").tag(60)
        Text("1 day before").tag(1440)
        Text("1 week before").tag(10080)
      }
      TextField("Notes", text: $value.notes, axis: .vertical).lineLimit(3...8)
    }
  }
}
struct PregnancyEditor: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(BloomModel.self) private var model
  @State var value: Pregnancy
  let revision: UInt64
  var body: some View {
    EditorFrame(
      title: "Pregnancy record", save: { await model.save("pregnancy", value, revision: revision) }
    ) {
      Section {
        DatePicker(
          "Record starts",
          selection: Binding(
            get: { BloomDate.date(value.start) }, set: { value.start = BloomDate.day($0) }),
          in: ...Date(), displayedComponents: .date)
        DatePicker(
          "Estimated due date",
          selection: Binding(
            get: { BloomDate.date(value.due) }, set: { value.due = BloomDate.day($0) }),
          displayedComponents: .date)
        Text(
          "Use the due date provided by your care team. The week counter uses that date; it cannot confirm dating or pregnancy health."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      BloomSection("History") {
        Toggle(
          "This pregnancy has ended",
          isOn: Binding(
            get: { value.ended != nil }, set: { value.ended = $0 ? BloomDate.day(Date()) : nil }))
        if value.ended != nil {
          DatePicker(
            "Ended",
            selection: Binding(
              get: { BloomDate.date(value.ended ?? value.start) },
              set: { date in value.ended = BloomDate.day(date) }), in: ...Date(),
            displayedComponents: .date)
          TextField("Outcome (optional)", text: $value.outcome)
        }
        TextField("Private notes", text: $value.notes, axis: .vertical).lineLimit(3...8)
        if model.archive.pregnancies.contains(where: { $0.id == value.id }) {
          Button("Delete pregnancy record", role: .destructive) {
            Task {
              await model.delete("delete_pregnancy", id: value.id)
              if !model.archive.pregnancies.contains(where: { $0.id == value.id }) { dismiss() }
            }
          }
        }
      }
    }
  }
}
@ViewBuilder func choices(_ values: [String]) -> some View {
  ForEach(values, id: \.self) {
    Text($0.replacingOccurrences(of: "_", with: " ").capitalized).tag($0)
  }
}
func splitTags(_ text: String) -> [String] {
  text.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter {
    !$0.isEmpty
  }
}
