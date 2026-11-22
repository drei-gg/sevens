import BloomCore
import SevenKit
import SwiftUI

let bloomCategories = [
  "Cramps", "Headache", "Fatigue", "Bloating", "Calm", "Focused", "Energetic", "Happy", "Anxiety",
  "Low mood", "Back pain", "Breast tenderness", "Nausea", "Acne", "Hot flashes", "Night sweats",
  "Sleep difficulty", "Pelvic pain", "Dryness",
]

struct CycleHero: View {
  @Environment(\.dynamicTypeSize) private var typeSize
  let dashboard: Dashboard
  let profile: Profile
  var body: some View {
    VStack(alignment: .leading, spacing: Tokens.Space.m) {
      if typeSize.isAccessibilitySize {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
          Text("Your cycle").textRole(.caption, emphasized: true)
          Text(prettyDay(dashboard.today)).textRole(.caption).foregroundStyle(Tokens.muted)
        }
      } else {
        HStack {
          Label("Your cycle", systemImage: "circle.hexagongrid").textRole(
            .caption, emphasized: true)
          Spacer()
          Text(prettyDay(dashboard.today)).textRole(.caption).foregroundStyle(Tokens.muted)
        }
      }
      if let week = dashboard.pregnancy_week {
        Text("Week \(week)").textRole(.hero)
        Text("Day \(dashboard.pregnancy_day ?? 0) · from your recorded due date").textRole(
          .secondary)
      } else if let n = dashboard.estimate_days, n >= 0 {
        HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.s) {
          Text(n == 0 ? "Today" : "\(n)").textRole(.hero)
          if n > 0 { Text(n == 1 ? "day" : "days").textRole(.title) }
        }
        Text("Until your next period estimate").textRole(.body)
        if let day = dashboard.cycle_day {
          Text("Cycle day \(day) · \(dashboard.sample_size) completed cycles used").textRole(
            .secondary
          ).foregroundStyle(Tokens.muted)
        }
      } else {
        Text(dashboard.cycle_day.map { day in "Day \(day)" } ?? "Start here").textRole(.hero)
        Text(
          dashboard.cycle_day == nil
            ? "A little context, at your own pace." : "Since your last recorded period"
        ).textRole(.body)
        Text(
          profile.mode == "postpartum"
            ? "Postpartum · observations first" : "Your observations, without a forecast"
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      ObservationWeek(dashboard: dashboard)
      DisclosureGroup("About this estimate") {
        Text(dashboard.prediction_note).textRole(.secondary).foregroundStyle(Tokens.muted)
        Text("Estimates are not contraception or a diagnosis.").textRole(.caption).foregroundStyle(
          Tokens.muted)
      }.textRole(.secondary)
    }.padding(.vertical, Tokens.Space.s)
  }
}
struct ObservationWeek: View {
  let dashboard: Dashboard
  var body: some View {
    VStack(alignment: .leading, spacing: Tokens.Space.s) {
      HStack(spacing: Tokens.Space.xs) {
        ForEach(0..<7, id: \.self) { offset in
          let date =
            Calendar.current.date(
              byAdding: .day, value: offset - 6, to: BloomDate.date(dashboard.today)) ?? Date()
          let day = BloomDate.day(date)
          let record = dashboard.observed_days.first { $0.day == day }
          let flow = record.map { $0.flow != "none" } ?? false
          VStack(spacing: Tokens.Space.s) {
            Text(date.formatted(.dateTime.weekday(.narrow))).textRole(.caption).foregroundStyle(
              Tokens.muted)
            RoundedRectangle(cornerRadius: Tokens.Radius.button)
              .fill(flow ? Tokens.dataWarm : record == nil ? Tokens.rule : Tokens.dataCool)
              .frame(height: flow ? 36 : 12).frame(height: 36, alignment: .bottom)
            Text(date.formatted(.dateTime.day())).textRole(.caption, emphasized: offset == 6)
          }.frame(maxWidth: .infinity).accessibilityElement(children: .ignore)
            .accessibilityLabel(
              "\(prettyDay(day)), \(flow ? "flow recorded" : record == nil ? "unlogged" : "observations recorded")"
            )
        }
      }
      .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
      Text("Your last 7 days · taller bars show recorded flow").textRole(.caption).foregroundStyle(
        Tokens.muted)
    }.padding(.vertical, Tokens.Space.s)
  }
}
struct PackView: View {
  let position: PlanPosition
  var body: some View {
    VStack(alignment: .leading, spacing: Tokens.Space.s) {
      HStack {
        Text("Day \(position.day) of \(position.total)").textRole(.rowTitle, emphasized: true)
        Spacer()
        Text(position.phase).textRole(.caption).foregroundStyle(Tokens.muted)
      }
      ProgressView(value: Double(position.day), total: Double(position.total))
        .accessibilityLabel("Pack position, day \(position.day) of \(position.total)")
      Text("\(position.active) active · \(position.total - position.active) break/placebo days")
        .textRole(.caption).foregroundStyle(Tokens.muted)
    }.padding(.vertical, Tokens.Space.s)
  }
}
struct TrackingLayoutScreen: View {
  @AppStorage("bloom.favorites") private var favorites = "Cramps,Headache,Fatigue,Calm"
  @AppStorage("bloom.showContact") private var showContact = false
  var body: some View {
    Form {
      BloomSection("Your quick log") {
        Text(
          "Pin the observations you use most. Everything else remains available under More observations."
        ).foregroundStyle(Tokens.muted)
        ForEach(bloomCategories, id: \.self) { name in
          Toggle(
            name,
            isOn: Binding(
              get: { splitTags(favorites).contains(name) },
              set: { on in
                var values = splitTags(favorites)
                values.removeAll { $0 == name }
                if on { values.append(name) }
                favorites = values.joined(separator: ",")
              }))
        }
      }
      BloomSection("Optional sections") {
        Toggle("Show sex and contact shortcuts", isOn: $showContact)
        Text(
          "Hiding a section never removes its records. Existing contact history remains available in Calendar."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
    }.navigationTitle("Make it yours").sevenScreenBackground()
  }
}
struct ObservationEditor: View {
  @Environment(BloomModel.self) private var model
  @State var value: TimedObservation
  let revision: UInt64
  @State private var number = ""
  var body: some View {
    EditorFrame(title: "Timed observation", save: save) {
      BloomSection("When") {
        DatePicker(
          "Recorded time",
          selection: Binding(
            get: { Date(timeIntervalSince1970: Double(value.at)) },
            set: {
              value.at = Int64($0.timeIntervalSince1970)
              value.day = BloomDate.day($0, zone: TimeZone(identifier: value.zone) ?? .current)
            }), in: ...Date()
        )
        .environment(\.timeZone, TimeZone(identifier: value.zone) ?? .current)
        Text("Time zone: \(value.zone)").textRole(.caption).foregroundStyle(Tokens.muted)
      }
      BloomSection("What you noticed") {
        Picker("Type", selection: $value.kind) {
          choices(["symptom", "flow", "temperature", "ovulation_test", "pregnancy_test", "product"])
        }
        .onChange(of: value.kind) { _, kind in
          value.period_start = false
          value.number = nil
          number = ""
          value.value = kind == "flow" ? "light" : kind.hasSuffix("_test") ? "negative" : ""
          value.label = kind == "symptom" ? "Cramps" : ""
          value.level = kind == "symptom" ? 1 : nil
        }
        if value.kind == "symptom" || value.kind == "product" {
          TextField(
            value.kind == "product" ? "Product, e.g. pad or cup" : "Observation name",
            text: $value.label)
        }
        if value.kind == "symptom" {
          Picker("Level", selection: Binding(get: { value.level ?? 0 }, set: { value.level = $0 }))
          {
            Text("None").tag(0)
            Text("Low").tag(1)
            Text("Medium").tag(2)
            Text("High").tag(3)
          }
        }
        if value.kind == "flow" {
          Picker("Flow", selection: $value.value) {
            choices(["none", "spotting", "light", "medium", "heavy"])
          }
          Toggle("Start of a new period", isOn: $value.period_start)
        }
        if value.kind.hasSuffix("_test") {
          Picker("Result", selection: $value.value) {
            choices(["negative", "positive", "unclear"])
          }
        }
        if value.kind == "temperature" {
          TextField("Temperature, \(BloomTemperature.unit)", text: $number).keyboardType(.decimalPad)
        }
        if value.kind == "product" {
          Picker("Fullness", selection: $value.value) {
            choices(["", "light", "partly_full", "full", "overflow"])
          }
          TextField("Measured amount, mL (optional)", text: $number).keyboardType(.decimalPad)
        }
      }
      BloomSection("Private notes") {
        TextField("Anything else", text: $value.notes, axis: .vertical).lineLimit(3...8)
        Text(
          "Each save is a separate observation. Daily summaries use the highest flow and symptom level; your timeline retains every entry."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
    }.onAppear {
      number =
        value.number.map { n in
          value.kind == "temperature" ? BloomTemperature.number(n) : String(n)
        } ?? ""
    }
  }
  func save() async -> Bool {
    if value.kind == "temperature" {
      do { value.number = try BloomTemperature.celsius(from: number) } catch {
        model.message = explain(error)
        return false
      }
      return await model.save("observation", value, revision: revision)
    }
    let text = number.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
    if !text.isEmpty && Double(text) == nil {
      model.message = "Enter a valid measured number."
      return false
    }
    value.number = Double(text)
    return await model.save("observation", value, revision: revision)
  }
}
struct ObservationRow: View {
  let value: TimedObservation
  var body: some View {
    VStack(alignment: .leading, spacing: Tokens.Space.xs) {
      Text(
        value.label.isEmpty
          ? value.kind.replacingOccurrences(of: "_", with: " ").capitalized : value.label
      ).textRole(.rowTitle)
      Text(
        [
          Date(timeIntervalSince1970: Double(value.at)).formatted(
            Date.FormatStyle(
              date: .omitted, time: .shortened,
              timeZone: TimeZone(identifier: value.zone) ?? .current)),
          value.zone,
          value.value.replacingOccurrences(of: "_", with: " "),
          value.level.map { level in "Level \(level)/3" } ?? "",
          value.number.map { number in
            value.kind == "temperature" ? BloomTemperature.text(number) : "\(number) mL"
          }
            ?? "",
        ].filter { part in !part.isEmpty }.joined(separator: " · ")
      )
      .textRole(.secondary).foregroundStyle(Tokens.muted)
    }
  }
}
struct ExclusionsScreen: View {
  @Environment(BloomModel.self) private var model
  @State private var editing: Exclusion?
  var body: some View {
    List {
      BloomSection("Keep observations, refine estimates") {
        Text(
          "Exclude intervals with missing records or a change in your circumstances. Pregnancy and dated hormonal plans are excluded automatically. Your recorded history stays intact."
        ).textRole(.secondary)
        Button("Exclude an interval", systemImage: "plus") { editing = Exclusion() }
      }
      ForEach(model.archive.exclusions) { value in
        Button {
          editing = value
        } label: {
          VStack(alignment: .leading) {
            Text(value.reason)
            Text("\(prettyDay(value.start)) – \(value.end.map(prettyDay) ?? "Ongoing")").textRole(
              .secondary
            ).foregroundStyle(Tokens.muted)
          }
        }.swipeActions {
          Button("Remove", role: .destructive) {
            Task { await model.delete("delete_exclusion", id: value.id) }
          }
        }
      }
    }.navigationTitle("Estimate exclusions").sevenScreenBackground()
      .sheet(item: $editing) { ExclusionEditor(value: $0, revision: model.archive.revision) }
  }
}
struct ExclusionEditor: View {
  @Environment(BloomModel.self) private var model
  @State var value: Exclusion
  let revision: UInt64
  var body: some View {
    EditorFrame(
      title: "Estimate exclusion",
      save: { await model.save("exclusion", value, revision: revision) }
    ) {
      TextField("Reason", text: $value.reason)
      DatePicker(
        "From",
        selection: Binding(
          get: { BloomDate.date(value.start) }, set: { value.start = BloomDate.day($0) }),
        in: ...Date(), displayedComponents: .date)
      Toggle(
        "Ongoing",
        isOn: Binding(
          get: { value.end == nil }, set: { value.end = $0 ? nil : BloomDate.day(Date()) }))
      if value.end != nil {
        DatePicker(
          "Through",
          selection: Binding(
            get: { BloomDate.date(value.end ?? value.start) },
            set: { value.end = BloomDate.day($0) }), in: ...Date(), displayedComponents: .date)
      }
      Text("Overlapping cycles stay in history but are not used for estimates.").textRole(
        .secondary
      ).foregroundStyle(Tokens.muted)
    }
  }
}
/// Celsius is stored; the person's region decides the unit shown and entered. The core converts.
enum BloomTemperature {
  static var fahrenheit: Bool { UnitTemperature(forLocale: .current) == .fahrenheit }
  static var unit: String { fahrenheit ? "°F" : "°C" }
  static func display(_ celsius: Double) -> Double {
    bloomTemperatureDisplay(celsius: celsius, fahrenheit: fahrenheit)
  }
  static func number(_ celsius: Double) -> String {
    display(celsius).formatted(
      .number.precision(.fractionLength(0...2)).grouping(.never))
  }
  static func text(_ celsius: Double) -> String { "\(number(celsius)) \(unit)" }
  /// nil for a blank field; throws the core's range message for an unusable value.
  static func celsius(from field: String) throws -> Double? {
    let text = field.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(
      of: ",", with: ".")
    if text.isEmpty { return nil }
    guard let value = Double(text) else {
      throw BloomFailure.message("Enter a valid temperature in \(unit).")
    }
    return try bloomTemperatureCelsius(value: value, fahrenheit: fahrenheit)
  }
}
