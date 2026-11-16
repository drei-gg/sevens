import BloomCore
import HealthKit
import SevenKit
import SwiftUI

@MainActor @Observable final class HealthBridge {
  private let store = HKHealthStore()
  private let flowType = HKObjectType.categoryType(forIdentifier: .menstrualFlow)!
  var busy = false
  var note =
    "Nothing is shared automatically. Health permission is separate from this app’s journal."
  var preview: [DayLog] = []
  func read(existing: [DayLog]) async {
    guard HKHealthStore.isHealthDataAvailable() else {
      note = "Apple Health is not available on this device."
      return
    }
    busy = true
    defer { busy = false }
    do {
      try await store.requestAuthorization(toShare: [], read: [flowType])
      let start = Calendar.current.date(byAdding: .year, value: -2, to: Date()) ?? Date()
      let predicate = HKQuery.predicateForSamples(withStart: start, end: Date())
      let samples: [HKCategorySample] = try await withCheckedThrowingContinuation { continuation in
        let query = HKSampleQuery(
          sampleType: flowType, predicate: predicate, limit: 20000,
          sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]
        ) { _, samples, error in
          if let error {
            continuation.resume(throwing: error)
          } else {
            continuation.resume(returning: samples as? [HKCategorySample] ?? [])
          }
        }
        store.execute(query)
      }
      var days: [String: DayLog] = [:]
      for sample in samples {
        let day = BloomDate.day(sample.startDate)
        guard !existing.contains(where: { $0.day == day }),
          sample.sourceRevision.source.bundleIdentifier != Bundle.main.bundleIdentifier
        else { continue }
        let flow: String
        switch sample.value {
        case HKCategoryValueMenstrualFlow.light.rawValue: flow = "light"
        case HKCategoryValueMenstrualFlow.medium.rawValue: flow = "medium"
        case HKCategoryValueMenstrualFlow.heavy.rawValue: flow = "heavy"
        default: continue
        }
        var log = days[day] ?? DayLog()
        if days[day] == nil {
          log.id = sample.uuid.uuidString.lowercased()
          log.day = day
        }
        let levels = ["none", "light", "medium", "heavy"]
        if (levels.firstIndex(of: flow) ?? 0) > (levels.firstIndex(of: log.flow) ?? 0) {
          log.flow = flow
        }
        log.period_start =
          log.period_start || (sample.metadata?[HKMetadataKeyMenstrualCycleStart] as? Bool == true)
        days[day] = log
      }
      preview = days.values.sorted { $0.day < $1.day }
      note =
        preview.isEmpty
        ? "No new readable flow records. Health does not reveal whether read permission was denied. Existing journal dates were skipped."
        : "Review \(preview.count) new days from the last two years. Existing dates are skipped; no notes or contact records are read."
    } catch { note = error.localizedDescription }
  }
  func write(_ archive: Archive) async {
    guard HKHealthStore.isHealthDataAvailable() else {
      note = "Apple Health is not available on this device."
      return
    }
    busy = true
    defer { busy = false }
    do {
      try await store.requestAuthorization(toShare: [flowType], read: [])
      guard store.authorizationStatus(for: flowType) == .sharingAuthorized else {
        note = "Flow sharing is not authorized. You can change this in Health."
        return
      }
      let samples: [HKCategorySample] = archive.days.compactMap { day in
        let value: HKCategoryValueMenstrualFlow
        switch day.flow {
        case "light": value = .light
        case "medium": value = .medium
        case "heavy": value = .heavy
        default: return nil
        }
        let start = Calendar.current.startOfDay(for: BloomDate.date(day.day))
        let end =
          Calendar.current.date(byAdding: .day, value: 1, to: start)
          ?? start.addingTimeInterval(86400)
        return HKCategorySample(
          type: flowType, value: value.rawValue, start: start, end: end,
          metadata: [
            HKMetadataKeySyncIdentifier: "bloom.flow.\(day.id)",
            HKMetadataKeySyncVersion: NSNumber(value: archive.revision + 1),
            HKMetadataKeyMenstrualCycleStart: day.period_start,
          ])
      }
      if samples.isEmpty {
        note = "No light, medium or heavy flow entries to share."
        return
      }
      try await store.save(samples)
      note =
        "Shared \(samples.count) flow entries. Notes and contacts stay here. Later journal deletions do not delete Health copies; manage those in Health."
    } catch { note = error.localizedDescription }
  }
}
struct HealthScreen: View {
  @Environment(BloomModel.self) private var model
  @State private var health = HealthBridge()
  @State private var share = false
  var body: some View {
    List {
      BloomSection("Menstrual flow") {
        Text(health.note).textRole(.body)
        Button("Read Health flow for review") {
          Task { await health.read(existing: model.archive.days) }
        }
        Button("Share journal flow to Health") { share = true }
        Text(
          "This connection covers light, medium and heavy flow. Spotting, symptoms, tests, temperatures, medications and contact records are not transferred."
        ).textRole(.secondary).foregroundStyle(Tokens.muted)
      }
      if !health.preview.isEmpty {
        BloomSection("Review new days") {
          ForEach(health.preview) { day in
            BloomValue(prettyDay(day.day), value: day.flow.capitalized)
          }
          Button("Import reviewed days") {
            Task {
              var incoming = Archive()
              incoming.days = health.preview
              if await model.importArchive(incoming) {
                health.preview = []
                health.note = "Reviewed flow entries imported."
              }
            }
          }
          Button("Discard review", role: .cancel) { health.preview = [] }
        }
      }
      if health.busy { ProgressView() }
    }.navigationTitle("Apple Health").sevenScreenBackground().disabled(health.busy)
      .confirmationDialog(
        "Share all logged light, medium and heavy flow to Apple Health?", isPresented: $share,
        titleVisibility: .visible
      ) { Button("Share flow") { Task { await health.write(model.archive) } } }
  }
}
