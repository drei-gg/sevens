import SwiftUI
import SevenKit
import AlarmCore

/// Alarms and Time loops: the next one big at the top, one way to add, then every routine as a
/// large time with its switch.
struct RoutinesScreen: View {
    @Environment(AlarmModel.self) private var model
    @Environment(\.dynamicTypeSize) private var textSize
    let kind: RoutineKind
    @State private var editing: Routine?
    @State private var deleting: Routine?
    @State private var search = ""
    @State private var customNap = false
    @State private var napsStarted = 0

    private var all: [Routine] { model.archive.routines.filter { $0.kind == kind } }
    var routines: [Routine] {
        let now = Date()
        return all.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) || ($0.group ?? "").localizedCaseInsensitiveContains(search) }
            .sorted {
                let left = $0.next(after: now) ?? .distantFuture, right = $1.next(after: now) ?? .distantFuture
                return left == right ? $0.name < $1.name : left < right
            }
    }
    var groups: [String] { Set(model.archive.routines.compactMap { let value = $0.group?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""; return value.isEmpty ? nil : value }).sorted() }

    var body: some View {
        List {
            Section {
                VStack(spacing: Tokens.Space.l) {
                    TimelineView(.periodic(from: .now, by: 30)) { context in hero(at: context.date) }
                    addButton
                }
                .padding(.vertical, Tokens.Space.m).frame(maxWidth: .infinity)
                .bareListRow()
            }
            if kind == .alarm { napSection }
            if model.archive.installed.contains(where: { $0.purpose == "followUp" }) {
                Section {
                    Button("I’m awake. Cancel wake-up checks", systemImage: "checkmark.circle") { Task { await model.clearWakeUpChecks() } }
                }
            }
            if !groups.isEmpty {
                Section {
                    ForEach(groups, id: \.self) { group in
                        Toggle(group, isOn: Binding(get: { model.archive.routines.filter { $0.group?.trimmingCharacters(in: .whitespacesAndNewlines) == group }.allSatisfy(\.enabled) },
                                                    set: { value in Task { await model.toggleGroup(group, enabled: value) } }))
                    }
                } header: { Text("Groups").foregroundStyle(Tokens.muted) }
            }
            if !search.isEmpty && routines.isEmpty {
                ContentUnavailableView.search(text: search)
            }
            ForEach(routines) { routine in
                Section {
                    RoutineRow(routine: routine, onEdit: { editing = routine },
                               onTry: { model.previewMission = routine },
                               onLog: { Task { await model.manual(routine, date: Date()) } })
                        .swipeActions {
                            Button("Delete", role: .destructive) { deleting = routine }
                            Button("Duplicate") { Task { await model.duplicate(routine) } }
                        }
                        .contextMenu {
                            Button("Edit", systemImage: "pencil") { editing = routine }
                            Button("Duplicate", systemImage: "plus.square.on.square") { Task { await model.duplicate(routine) } }
                            if routine.mission != .none { Button("Try mission", systemImage: routine.mission.symbol) { model.previewMission = routine } }
                            Button("Log done", systemImage: "checkmark") { Task { await model.manual(routine, date: Date()) } }
                            Divider()
                            Button("Delete", systemImage: "trash", role: .destructive) { deleting = routine }
                        }
                }
            }
            Section {
                Label { Text(model.scheduling).textRole(.caption) } icon: { Image(systemName: "bell.badge").foregroundStyle(Tokens.muted) }
                    .accessibilityIdentifier("scheduling-status")
                if !model.isPreview { Button("Allow alarms and reminders") { Task { await model.authorize() } } }
            }
        }
        .listSectionSpacing(.compact)
        .sevenScreenBackground()
        .navigationTitle(kind == .alarm ? "Alarms" : "Time loops")
        .modifier(SearchWhenMany(count: all.count, text: $search))
        .sensoryFeedback(.success, trigger: napsStarted)
        .sheet(item: $editing) { RoutineEditor(routine: $0) }
        .sheet(isPresented: $customNap) { CustomNapSheet() }
        .confirmationDialog("Delete this routine? History will stay.", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("Delete routine", role: .destructive) { if let r = deleting { Task { await model.delete(r) } }; deleting = nil }
        }
    }

    // MARK: Hero

    /// The next routine to ring, big, in a 24-hour dial. At accessibility sizes the dial steps
    /// aside and the words stack.
    @ViewBuilder private func hero(at now: Date) -> some View {
        let upcoming = all.compactMap { routine in routine.next(after: now).map { (routine, $0) } }.min { $0.1 < $1.1 }
        let center = VStack(spacing: Tokens.Space.xs) {
            if let upcoming {
                let routine = upcoming.0, date = upcoming.1
                Text(kind == .alarm ? "Next alarm" : "Next loop").textRole(.caption, emphasized: true).textCase(.uppercase)
                ClockText(parts: ClockFormat.parts(date), role: .display, periodRole: .body)
                    .accessibilityIdentifier("next-time")
                Text(routine.name).textRole(.rowTitle, emphasized: true).lineLimit(2)
                Text("\(ClockFormat.day(date, from: now)), \(ClockFormat.countdown(to: date, from: now))").textRole(.caption).numeric()
            } else {
                Image(systemName: kind == .alarm ? "alarm" : "repeat").glyph()
                Text(all.isEmpty ? (kind == .alarm ? "No alarms yet" : "No loops yet") : (kind == .alarm ? "All alarms off" : "All loops off"))
                    .textRole(.section)
                Text(all.isEmpty ? (kind == .alarm ? "Add a wake-up time and an optional mission." : "Several times. Different weekdays. One routine.")
                     : "Turn one on below, or add another.")
                    .textRole(.caption)
            }
        }
        .multilineTextAlignment(.center)
        Group {
            if textSize.isAccessibilitySize {
                center.frame(maxWidth: .infinity)
            } else {
                ZStack {
                    DayDial(now: now, next: upcoming?.1)
                    center.padding(Tokens.Space.xxl)
                }
                .frame(maxWidth: 320).aspectRatio(1, contentMode: .fit)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("next-hero")
    }

    /// One large way to add: the screen's only filled button.
    private var addButton: some View {
        Button { editing = model.newRoutine(kind: kind) } label: {
            Label(kind == .alarm ? "Add alarm" : "Add loop", systemImage: "plus")
                .textRole(.rowTitle, emphasized: true).frame(maxWidth: .infinity)
        }
        .sevenProminentButton().controlSize(.extraLarge).buttonBorderShape(.capsule)
        .frame(maxWidth: 420)
        .accessibilityIdentifier("add-routine")
    }

    // MARK: Quick naps

    private var napSection: some View {
        Section {
            VStack(alignment: .leading, spacing: Tokens.Space.ms) {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.s), count: textSize.isAccessibilitySize ? 2 : 4),
                          spacing: Tokens.Space.s) {
                    ForEach([15, 20, 30, 60], id: \.self) { minutes in
                        Button { Task { if await model.nap(minutes: minutes) { napsStarted += 1 } } } label: {
                            ChipLabel(value: minutes.formatted(), unit: String(localized: "min"))
                        }
                        .quickAddChip()
                        .accessibilityLabel(String(localized: "Nap for \(ClockFormat.minutes(minutes))"))
                        .accessibilityHint("Sets a one-off alarm")
                        .accessibilityIdentifier("nap-\(minutes)")
                    }
                }
                Button("Custom nap…", systemImage: "timer") { customNap = true }
                    .textRole(.body).accessibilityIdentifier("custom-nap")
            }
            .padding(.horizontal, Tokens.Space.m)
            .bareListRow()
        } header: {
            Text("Quick nap").foregroundStyle(Tokens.muted)
        }
    }
}

/// Search appears once there are enough routines to need it (polish guide §5.1).
private struct SearchWhenMany: ViewModifier {
    let count: Int
    @Binding var text: String
    @ViewBuilder func body(content: Content) -> some View {
        if count >= 8 { content.searchable(text: $text, prompt: "Find a routine or group") } else { content }
    }
}

/// One routine: its time large, its name and days under it, and its switch (system green).
/// Routines with several times list each time with its own days. Tapping the words edits it.
struct RoutineRow: View {
    @Environment(AlarmModel.self) private var model
    let routine: Routine
    let onEdit: () -> Void
    let onTry: () -> Void
    let onLog: () -> Void

    private var single: Bool { routine.timing == .once || (routine.timing == .weekly && routine.slots.count == 1) }

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            HStack(alignment: single ? .center : .top, spacing: Tokens.Space.m) {
                Button(action: onEdit) { summary.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()) }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityHint("Edits this routine")
                    .accessibilityIdentifier("edit-\(routine.name)")
                Toggle(routine.name, isOn: Binding(get: { routine.enabled }, set: { value in Task { await model.toggle(routine, enabled: value) } }))
                    .labelsHidden()
                    .accessibilityLabel(routine.name)
                    .accessibilityIdentifier("routine-\(routine.name)")
            }
            HStack(spacing: Tokens.Space.s) {
                if routine.mission != .none {
                    Button(action: onTry) { Label(routine.mission.title, systemImage: routine.mission.symbol) }
                        .rowChip()
                        .accessibilityLabel(String(localized: "Try mission: \(routine.mission.title)"))
                        .accessibilityIdentifier("try-mission")
                }
                if routine.kind == .loop {
                    Button(action: onLog) { Label("Log done", systemImage: "checkmark") }
                        .rowChip().accessibilityIdentifier("log-done")
                }
            }
        }
        .padding(.vertical, Tokens.Space.xs)
    }

    @ViewBuilder private var summary: some View {
        let dim = !routine.enabled
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            if single {
                ClockText(parts: routine.timing == .once ? ClockFormat.parts(routine.once) : ClockFormat.parts(minute: routine.slots[0].minute))
                    .foregroundStyle(dim ? Tokens.muted : Color.primary)
                HStack(spacing: Tokens.Space.s) {
                    Text(routine.name).textRole(.rowTitle).lineLimit(2)
                    if let group = routine.group, !group.isEmpty { Text(group).textRole(.caption) }
                }
                if routine.timing == .once {
                    Text(routine.once.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())).textRole(.caption)
                } else {
                    DayLetters(days: routine.slots[0].weekdays, dimmed: dim)
                }
            } else {
                HStack(spacing: Tokens.Space.s) {
                    Text(routine.name).textRole(.rowTitle, emphasized: true).lineLimit(2)
                    if let group = routine.group, !group.isEmpty { Text(group).textRole(.caption) }
                }
                if routine.timing == .window {
                    Text("\(clockText(routine.windowStart))–\(clockText(routine.windowEnd))").textRole(.section).numeric()
                        .foregroundStyle(dim ? Tokens.muted : Color.primary)
                    Text("Every \(ClockFormat.minutes(routine.intervalMinutes))").textRole(.caption)
                    DayLetters(days: routine.windowDays, dimmed: dim)
                } else {
                    ForEach(routine.slots) { slot in
                        HStack(alignment: .firstTextBaseline, spacing: Tokens.Space.ms) {
                            ClockText(parts: ClockFormat.parts(minute: slot.minute), role: .section, periodRole: .caption)
                                .foregroundStyle(dim ? Tokens.muted : Color.primary)
                            Spacer(minLength: 0)
                            DayLetters(days: slot.weekdays, dimmed: dim)
                        }
                    }
                }
            }
            NextOccurrenceLabel(routine: routine)
        }
    }
}
