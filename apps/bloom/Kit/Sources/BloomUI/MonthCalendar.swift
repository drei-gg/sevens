import BloomCore
import SevenKit
import SwiftUI

struct MonthCalendar: View {
  @Environment(BloomModel.self) private var model
  @Binding var selection: Date
  @State private var month = Date()
  private var calendar: Calendar {
    var c = Calendar(identifier: .gregorian)
    c.firstWeekday = Calendar.current.firstWeekday
    return c
  }
  private var start: Date {
    calendar.date(from: calendar.dateComponents([.year, .month], from: month)) ?? month
  }
  private var count: Int { calendar.range(of: .day, in: .month, for: month)?.count ?? 30 }
  private var offset: Int {
    (calendar.component(.weekday, from: start) - calendar.firstWeekday + 7) % 7
  }
  var body: some View {
    VStack(spacing: Tokens.Space.ms) {
      HStack {
        Button {
          move(-1)
        } label: {
          Image(systemName: "chevron.left")
        }.accessibilityLabel("Previous month")
        Spacer()
        Text(month.formatted(.dateTime.month(.wide).year())).textRole(.rowTitle, emphasized: true)
        Spacer()
        Button {
          move(1)
        } label: {
          Image(systemName: "chevron.right")
        }.accessibilityLabel("Next month")
      }.buttonStyle(.bordered)
      LazyVGrid(
        columns: Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.xs), count: 7),
        spacing: Tokens.Space.s
      ) {
        ForEach(0..<7, id: \.self) { index in
          Text(calendar.veryShortWeekdaySymbols[(calendar.firstWeekday - 1 + index) % 7]).textRole(
            .caption
          ).accessibilityHidden(true)
        }
        ForEach(0..<(offset + count), id: \.self) { index in
          if index < offset {
            Color.clear.frame(minHeight: 44).accessibilityHidden(true)
          } else {
            cell(index - offset + 1)
          }
        }
      }
      .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
      ViewThatFits(in: .horizontal) {
        HStack(spacing: Tokens.Space.m) { legend }
        VStack(alignment: .leading, spacing: Tokens.Space.s) { legend }
      }.textRole(.caption).foregroundStyle(Tokens.muted)
      if let next = model.dashboard?.next_period,
        calendar.isDate(BloomDate.date(next), equalTo: month, toGranularity: .month)
      {
        Text(
          "Period estimate: \(prettyDay(next)). Estimates are shown separately from observations."
        ).textRole(.secondary)
      }
    }.padding(.vertical, Tokens.Space.s)
  }
  @ViewBuilder private var legend: some View {
    Label("Flow logged", systemImage: "drop.fill")
    Label("Entry", systemImage: "circle.fill")
    Label("Estimate", systemImage: "circle.dashed")
  }
  @ViewBuilder private func cell(_ number: Int) -> some View {
    let date = calendar.date(byAdding: .day, value: number - 1, to: start) ?? start
    let key = BloomDate.day(date)
    let record = model.dashboard?.observed_days.first { $0.day == key }
    let estimateStart = model.dashboard?.earliest ?? model.dashboard?.next_period
    let estimateEnd = model.dashboard?.latest ?? model.dashboard?.next_period
    let estimated = estimateStart.map { key >= $0 && key <= (estimateEnd ?? $0) } ?? false
    let flow = record.map { ["light", "medium", "heavy", "spotting"].contains($0.flow) } ?? false
    let selected = calendar.isDate(date, inSameDayAs: selection)
    Button {
      selection = date
    } label: {
      VStack(spacing: Tokens.Space.xs) {
        Text("\(number)").textRole(.body, emphasized: selected)
        Image(systemName: flow ? "drop.fill" : estimated ? "circle.dashed" : "circle.fill")
          .textRole(.caption).foregroundStyle(
            flow ? Tokens.dataWarm : Tokens.muted
          ).opacity(record == nil && !estimated ? 0 : 1)
      }.frame(maxWidth: .infinity, minHeight: 44).overlay {
        if selected {
          RoundedRectangle(cornerRadius: Tokens.Radius.button).stroke(Tokens.ink, lineWidth: 2)
        }
      }
    }.buttonStyle(.plain).accessibilityLabel(
      "\(prettyDay(key)), \(flow ? "flow logged" : estimated ? "period start estimate" : record == nil ? "not logged" : "journal entry")"
    ).accessibilityAddTraits(selected ? .isSelected : [])
  }
  private func move(_ amount: Int) {
    month = calendar.date(byAdding: .month, value: amount, to: month) ?? month
  }
}
