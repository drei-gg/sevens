import BloomCore
import Charts
import SevenKit
import SwiftUI

public struct BloomRootView: View {
  @Environment(BloomModel.self) private var model
  @State private var before = false
  public init() {}
  public var body: some View {
    @Bindable var model = model
    Group {
      if model.locked {
        ContentUnavailableView {
          Label("Your records are locked", systemImage: "lock")
        } description: {
          Text("Authenticate to open your private journal.")
        } actions: {
          Button(model.authenticating ? "Authenticating…" : "Unlock records") {
            Task { await model.unlock() }
          }.sevenProminentButton().disabled(model.authenticating)
        }
      } else if model.ready {
        TabView {
          NavigationStack { TodayScreen() }.tabItem { Label("Today", systemImage: "sun.max") }
          NavigationStack { CalendarScreen() }.tabItem {
            Label("Calendar", systemImage: "calendar")
          }
          NavigationStack { CareScreen() }.tabItem { Label("Care", systemImage: "pills") }
          NavigationStack { InsightsScreen() }.tabItem {
            Label("Insights", systemImage: "chart.xyaxis.line")
          }
          NavigationStack { SettingsScreen() }.tabItem {
            Label("Settings", systemImage: "gearshape")
          }
        }.sevenAdaptiveTabs()
      } else if let failure = model.failure {
        ContentUnavailableView {
          Label("Records could not open", systemImage: "lock.doc")
        } description: {
          Text(failure)
        } actions: {
          Button("Try again") { Task { await model.retry() } }
        }
      } else {
        ProgressView("Opening your records")
      }
    }
    .sevenLook().toggleStyle(SystemSwitchStyle())
    .task(id: model.store.hasStarted) {
      if model.store.hasStarted && model.store.launchSheet == .beforeYouBuy { before = true }
    }
    .sheet(isPresented: $before) {
      BeforeYouBuyView(content: BloomGate.before) { _ in before = false }
        .interactiveDismissDisabled()
    }
    .unlockSheet(isPresented: $model.showUnlock).trialEndedSheet(content: BloomGate.expired)
    .alert(
      "Your records",
      isPresented: Binding(
        get: { model.message != nil }, set: { shown in if !shown { model.message = nil } })
    ) {
      Button("OK") { model.message = nil }
    } message: {
      Text(model.message ?? "")
    }
    .onChange(of: model.store.state) { _, _ in model.publishWatch() }
    .sevenStore(model.store, unlock: BloomGate.unlock)
  }
}
enum BloomGate {
  static let unlock = UnlockContent(
    appName: Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String
      ?? "Cycle & Health",
    features: [
      "Period, symptom and private sex tracking",
      "Pill packs, birth-control schedules and dose history",
      "Patterns, reports and encrypted backups", "Watch companion and discreet widgets",
      "Family Sharing: separate, private records for everyone",
    ])
  static let before = BeforeYouBuyContent(
    title: "Health already tracks your cycle.",
    paragraphs: [
      .init(
        "Apple Health includes Cycle Tracking and medication reminders. If that covers you, keep your money."
      ),
      .init(
        "This adds flexible pill packs, a private journal, symptom patterns, portable reports and encrypted backups. Try everything for 7 days. Then {price}, once, with Family Sharing. Nothing renews.",
        withoutPrice:
          "Try everything for 7 days, then unlock once with Family Sharing. Nothing renews."),
      .init(
        "Each person keeps their own records. Predictions are estimates, never contraception or a diagnosis. Your history, corrections, exports and existing dose tracking remain available after the trial."
      ),
    ], justScanLabel: "Look around first")
  static let expired = TrialEndedContent(
    stillFree: [
      "Reading, correcting, deleting and exporting records",
      "Recording doses on existing active plans",
    ], unlockKeeps: ["Adding new journals, plans, appointments and pregnancies"])
}
struct TodayScreen: View {
  @Environment(BloomModel.self) private var model
  @State private var day: DayLog?
  @State private var contact: Contact?
  @State private var observation: TimedObservation?
  @AppStorage("bloom.showContact") private var showContact = false
  var body: some View {
    List {
      if let d = model.dashboard {
        Section {
          CycleHero(dashboard: d, profile: model.archive.profile)
          Button {
            day = model.archive.days.first { $0.day == d.today } ?? DayLog()
          } label: {
            Label("Log today", systemImage: "plus.circle")
          }.accessibilityIdentifier("logToday")
          Button("Add timed observation", systemImage: "clock.badge.plus") {
            observation = TimedObservation()
          }
          if showContact {
            Button {
              contact = Contact()
            } label: {
              Label("Log sex or contact", systemImage: "heart.text.clipboard")
            }
          }
        }
        BloomSection("Today’s reminders") {
          if d.today_doses.isEmpty {
            Text("No doses scheduled today.").foregroundStyle(Tokens.muted)
          }
          ForEach(d.today_doses) { dose in DoseRow(dose: dose) }
          NavigationLink("Manage pill and birth-control plans") { CareScreen() }
        }
        if !d.plan_positions.isEmpty {
          BloomSection("Pack progress") {
            ForEach(d.plan_positions) { position in
              VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                if let name = model.archive.plans.first(where: { $0.id == position.id })?.name,
                  !name.isEmpty
                {
                  Text(name).textRole(.secondary).foregroundStyle(Tokens.muted)
                }
                PackView(position: position)
              }
            }
          }
        }
        if !d.stock.isEmpty {
          BloomSection("Supply") {
            ForEach(d.stock) { stock in
              BloomValue(stock.name, value: "\(stock.remaining) remaining")
            }
          }
        }
        if let entry = model.archive.observations.sorted(by: { $0.at > $1.at }).first {
          BloomSection("Recently recorded") {
            Button {
              observation = entry
            } label: {
              ObservationRow(value: entry)
            }
          }
        }
        BloomSection("A private space") {
          Text(
            "Log what matters to you. Blank days stay unknown; reminders never mark a dose as taken."
          ).textRole(.secondary).foregroundStyle(Tokens.muted)
          NavigationLink("Guides and sources") { GuidesScreen() }
        }
      }
    }.navigationTitle("Today").sevenScreenBackground()
      .sheet(item: $day) { DayEditor(value: $0, revision: model.archive.revision) }
      .sheet(item: $contact) { ContactEditor(value: $0, revision: model.archive.revision) }
      .sheet(item: $observation) { ObservationEditor(value: $0, revision: model.archive.revision) }
  }
}
struct DoseRow: View {
  @Environment(BloomModel.self) private var model
  let dose: Occurrence
  /// A later day's dose cannot be recorded yet; the core rejects it.
  var upcoming: Bool { dose.day > BloomDate.day(Date()) }
  var body: some View {
    VStack(alignment: .leading, spacing: Tokens.Space.s) {
      Text(dose.title).textRole(.rowTitle)
      Text(
        "\(Date(timeIntervalSince1970: Double(dose.at)).formatted(date: .omitted, time: .shortened)) · \(dose.phase.capitalized) · \(dose.status.capitalized)"
      ).textRole(.secondary).foregroundStyle(Tokens.muted)
      if upcoming {
        Text("You can record this dose on its scheduled day.").textRole(.secondary)
          .foregroundStyle(Tokens.muted)
      } else {
        HStack {
          Button("Taken") { Task { await model.dose(dose, status: "taken") } }
          Button("Skipped") { Task { await model.dose(dose, status: "skipped") } }
          if dose.status != "taken"
            && BloomDate.day(Date(timeIntervalSince1970: Double(dose.at))) == BloomDate.day(Date())
          {
            Button("10 min") { Task { await model.dose(dose, status: "snoozed") } }
              .accessibilityLabel("Snooze ten minutes")
          }
        }.buttonStyle(.bordered).disabled(model.busy)
      }
    }.padding(.vertical, Tokens.Space.xs)
  }
}
struct CalendarScreen: View {
  @Environment(BloomModel.self) private var model
  @State private var selected = Date()
  @State private var query = ""
  @State private var scheduled: [Occurrence] = []
  @State private var day: DayLog?
  @State private var contact: Contact?
  @State private var observation: TimedObservation?
  @AppStorage("bloom.showContact") private var showContact = false
  var selectedDay: String { BloomDate.day(selected) }
  var records: [DayLog] {
    model.archive.days.filter { record in
      query.isEmpty
        ? record.day == selectedDay
        : (record.notes + " " + record.tags.joined(separator: " ") + " "
          + record.symptoms.keys.joined(separator: " ") + " " + record.day)
          .localizedCaseInsensitiveContains(query)
    }.sorted { first, second in first.day > second.day }
  }
  var contacts: [Contact] {
    model.archive.contacts.filter { record in
      query.isEmpty
        ? record.day == selectedDay
        : (record.notes + " " + record.activity + " " + record.day)
          .localizedCaseInsensitiveContains(query)
    }.sorted { first, second in first.at > second.at }
  }
  var body: some View {
    List {
      if query.isEmpty {
        MonthCalendar(selection: $selected)
        BloomSection(prettyDay(selectedDay)) {
          Button("Log this day") {
            var v = model.archive.days.first { $0.day == selectedDay } ?? DayLog()
            v.day = selectedDay
            day = v
          }.disabled(selectedDay > BloomDate.day(Date()))
        }
      }
      BloomSection("Journal") {
        ForEach(records) { record in
          Button {
            day = record
          } label: {
            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
              Text(
                "\(prettyDay(record.day)) · \(record.flow == "none" ? "No flow logged" : record.flow.capitalized + " flow")"
              ).textRole(.rowTitle)
              Text(record.symptoms.filter { $0.value > 0 }.keys.sorted().joined(separator: ", "))
                .textRole(.secondary).foregroundStyle(Tokens.muted)
              if !record.notes.isEmpty { Text(record.notes).lineLimit(2).textRole(.secondary) }
            }
          }.swipeActions {
            Button("Delete", role: .destructive) {
              Task { await model.delete("delete_day", id: record.id) }
            }
          }
        }
        if records.isEmpty { Text("No journal entries here.").foregroundStyle(Tokens.muted) }
      }
      BloomSection("Timed observations") {
        Button("Add timed observation") {
          var value = TimedObservation()
          if selectedDay != BloomDate.day(Date()) {
            value.at = Int64(selected.timeIntervalSince1970)
            value.day = selectedDay
          }
          observation = value
        }.disabled(selectedDay > BloomDate.day(Date()))
        ForEach(
          model.archive.observations.filter { entry in
            query.isEmpty
              ? entry.day == selectedDay
              : (entry.label + " " + entry.value + " " + entry.day)
                .localizedCaseInsensitiveContains(query)
          }.sorted { $0.at > $1.at }
        ) { value in
          Button {
            observation = value
          } label: {
            ObservationRow(value: value)
          }
          .swipeActions {
            Button("Delete", role: .destructive) {
              Task { await model.delete("delete_observation", id: value.id) }
            }
          }
        }
      }
      if showContact || !contacts.isEmpty {
        BloomSection("Sex and contact") {
          ForEach(contacts) { record in
            Button {
              contact = record
            } label: {
              BloomValue(record.activity, value: prettyDay(record.day))
            }.swipeActions {
              Button("Delete", role: .destructive) {
                Task { await model.delete("delete_contact", id: record.id) }
              }
            }
          }
        }
      }
      BloomSection("Scheduled on this day") {
        ForEach(scheduled) { dose in DoseRow(dose: dose) }
      }
      BloomSection("Recorded doses") {
        ForEach(
          model.archive.doses.filter { $0.day == selectedDay }.sorted {
            $0.scheduled < $1.scheduled
          }
        ) { dose in
          VStack(alignment: .leading) {
            Text(model.archive.plans.first { $0.id == dose.plan_id }?.name ?? "Archived plan")
            Text(dose.status.capitalized).foregroundStyle(Tokens.muted)
            Menu("Correct record") {
              ForEach(["taken", "skipped"], id: \.self) { status in
                Button(status.capitalized) {
                  var corrected = dose
                  corrected.status = status
                  corrected.recorded = Int64(Date().timeIntervalSince1970)
                  Task { _ = await model.save("dose", corrected, revision: model.archive.revision) }
                }
              }
              Button("Remove record", role: .destructive) {
                Task { await model.delete("delete_dose", id: dose.id) }
              }
            }
          }
        }
      }
    }.navigationTitle("Calendar").searchable(text: $query, prompt: "Notes, dates, symptoms or tags")
      .sevenScreenBackground()
      .task(id: selectedDay + String(model.archive.revision)) {
        scheduled =
          (try? await model.repository.dashboard(now: BloomDate.date(selectedDay)))?.today_doses
          ?? []
      }
      .sheet(item: $day) { DayEditor(value: $0, revision: model.archive.revision) }.sheet(
        item: $contact
      ) { ContactEditor(value: $0, revision: model.archive.revision) }
      .sheet(item: $observation) { ObservationEditor(value: $0, revision: model.archive.revision) }
  }
}
struct CareScreen: View {
  @Environment(BloomModel.self) private var model
  @State private var plan: Plan?
  @State private var appointment: Appointment?
  @State private var pregnancy: Pregnancy?
  @State private var refill: Plan?
  var body: some View {
    List {
      BloomSection("Pills and birth control") {
        ForEach(model.archive.plans) { p in
          VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Button {
              plan = p
            } label: {
              BloomValue(p.name, value: p.enabled ? p.pattern.capitalized : "Paused")
            }
            if let position = model.dashboard?.plan_positions.first(where: { $0.id == p.id }) {
              PackView(position: position)
            }
            if let stock = model.dashboard?.stock.first(where: { $0.plan_id == p.id }) {
              Text("\(stock.remaining) remaining").textRole(.secondary).foregroundStyle(
                Tokens.muted)
            }
            if p.initial_stock != nil {
              Button("Record a refill") { refill = p }
              NavigationLink("Refill history") { RefillHistory(plan: p) }
            }
          }.swipeActions {
            if p.enabled {
              Button("Pause") { Task { await model.delete("delete_plan", id: p.id) } }
            }
          }
        }
        Button("Add a plan", systemImage: "plus") { plan = Plan() }.accessibilityIdentifier(
          "addPlan")
      }
      BloomSection("Pregnancy") {
        ForEach(model.archive.pregnancies) { p in
          Button {
            pregnancy = p
          } label: {
            BloomValue(
              p.ended == nil ? "Current pregnancy" : "Pregnancy history",
              value: "Due \(prettyDay(p.due))")
          }
        }
        Button("Add pregnancy") { pregnancy = Pregnancy() }
      }
      BloomSection("Appointments and reminders") {
        ForEach(model.archive.appointments.sorted { $0.at < $1.at }) { a in
          Button {
            appointment = a
          } label: {
            VStack(alignment: .leading) {
              Text(a.title)
              Text(
                Date(timeIntervalSince1970: Double(a.at)).formatted(
                  date: .abbreviated, time: .shortened)
              ).textRole(.secondary).foregroundStyle(Tokens.muted)
            }
          }.swipeActions {
            Button("Delete", role: .destructive) {
              Task { await model.delete("delete_appointment", id: a.id) }
            }
          }
        }
        Button("Add appointment") { appointment = Appointment() }
      }
      BloomSection("Reminder delivery") {
        Text(model.notificationStatus).textRole(.secondary)
        if let at = model.dashboard?.coverage_until {
          Text(
            "Prepared reminder queue ends \(Date(timeIntervalSince1970: Double(at)).formatted(date: .abbreviated, time: .shortened)). Reopen the app to extend the queue."
          ).textRole(.secondary).foregroundStyle(Tokens.muted)
        }
        Button("Enable notifications") { Task { await model.allowNotifications() } }
        Text(
          "Schedules follow your prescription. For a missed dose, check your medicine’s leaflet or ask a pharmacist; this app does not calculate replacement doses."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
    }.navigationTitle("Care").sevenScreenBackground()
      .sheet(item: $plan) { PlanEditor(value: $0, revision: model.archive.revision) }
      .sheet(item: $appointment) { AppointmentEditor(value: $0, revision: model.archive.revision) }
      .sheet(item: $pregnancy) { PregnancyEditor(value: $0, revision: model.archive.revision) }
      .sheet(item: $refill) { RefillEditor(plan: $0, revision: model.archive.revision) }
  }
}
struct InsightsScreen: View {
  @Environment(BloomModel.self) private var model
  var body: some View {
    List {
      if let d = model.dashboard {
        BloomSection("Your recorded rhythm") {
          Text(d.history_note).textRole(.secondary).foregroundStyle(Tokens.muted)
          NavigationLink("Manage estimate exclusions") { ExclusionsScreen() }
          if d.cycles.count > 1 {
            Chart(d.cycles.filter { $0.length != nil && $0.excluded_reason == nil }) { cycle in
              BarMark(
                x: .value("Start", prettyDay(cycle.start)),
                y: .value("Days", cycle.length ?? 0)
              ).foregroundStyle(Tokens.dataWarm)
            }.bloomChartLabels().frame(height: 190).accessibilityLabel("Cycle length history")
          }
          ForEach(d.cycles.reversed()) { cycle in
            BloomValue(
              prettyDay(cycle.start),
              value: cycle.length.map { length in "\(length) days · \(cycle.bleeding_days) bleeding"
              } ?? "Current · \(cycle.bleeding_days) bleeding")
            if let reason = cycle.excluded_reason {
              Text("Excluded: \(reason)").textRole(.caption).foregroundStyle(Tokens.muted)
            }
          }
          if d.cycles.isEmpty {
            Text("Log period starts to see your history.").foregroundStyle(Tokens.muted)
          }
        }
        BloomSection("Estimates") {
          Text(d.prediction_note)
          if let start = d.earliest, let end = d.latest {
            BloomValue("Next period range", value: "\(prettyDay(start)) – \(prettyDay(end))")
          }
          if let start = d.fertile_start, let end = d.fertile_end {
            Text(
              "Possible fertile window: \(prettyDay(start)) – \(prettyDay(end)). This calendar estimate does not confirm ovulation and must not be used to avoid pregnancy."
            )
          }
        }
        BloomSection("Symptoms · last 180 days") {
          ForEach(d.symptoms) { s in
            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
              Text(s.name).textRole(.rowTitle)
              ProgressView(value: Double(s.count), total: Double(max(1, s.logged_days)))
              Text(
                "\(s.count) of \(s.logged_days) logged days · average when present \(s.average, specifier: "%.1f")/3"
              ).textRole(.secondary)
              Text("\(s.preperiod_count) occurrences within 7 days before a recorded period.")
                .textRole(.secondary).foregroundStyle(Tokens.muted)
            }
          }
          Text("These are patterns in your entries, not diagnoses. Unlogged days are excluded.")
            .textRole(.secondary).foregroundStyle(Tokens.muted)
        }
        if !d.temperatures.isEmpty {
          BloomSection("Basal temperature · \(BloomTemperature.unit)") {
            Chart(d.temperatures) { day in
              PointMark(
                x: .value(
                  "Date",
                  day.at.map { timestamp in Date(timeIntervalSince1970: Double(timestamp)) }
                    ?? BloomDate.date(day.day)), y: .value("Temperature", BloomTemperature.display(day.value))
              ).foregroundStyle(Tokens.dataCool)
            }.chartYScale(domain: .automatic(includesZero: false)).bloomChartLabels().frame(
              height: 190)
            Text("Recorded measurements only. This chart does not confirm ovulation.").textRole(
              .secondary)
          }
        }
        BloomSection("Doses recorded") {
          BloomValue("Taken", value: "\(d.taken)")
          BloomValue("Skipped", value: "\(d.skipped)")
        }
        Section {
          NavigationLink("Create a report") { ReportScreen() }
          NavigationLink("Guides and sources") { GuidesScreen() }
        }
      }
    }.navigationTitle("Insights").sevenScreenBackground()
  }
}
func prettyDay(_ day: String) -> String {
  BloomDate.date(day).formatted(date: .abbreviated, time: .omitted)
}
struct SystemSwitchStyle: ToggleStyle {
  func makeBody(configuration: Configuration) -> some View {
    Toggle(configuration).toggleStyle(.switch).tint(nil)
  }
}

struct BloomSection<Content: View>: View {
  let title: String
  @ViewBuilder var content: () -> Content
  init(_ title: String, @ViewBuilder content: @escaping () -> Content) {
    self.title = title
    self.content = content
  }
  var body: some View {
    Section {
      content()
    } header: {
      Text(title).textRole(.caption, emphasized: true).foregroundStyle(Tokens.muted)
    }
  }
}
struct BloomValue: View {
  let title: String
  let value: String
  init(_ title: String, value: String) {
    self.title = title
    self.value = value
  }
  var body: some View {
    LabeledContent(title) { Text(value).textRole(.body).foregroundStyle(Tokens.muted) }
  }
}

extension View {
  fileprivate func bloomChartLabels() -> some View {
    chartXAxis {
      AxisMarks {
        AxisGridLine()
        AxisValueLabel().foregroundStyle(Tokens.muted)
      }
    }
    .chartYAxis {
      AxisMarks {
        AxisGridLine()
        AxisValueLabel().foregroundStyle(Tokens.muted)
      }
    }
  }
}
