import SwiftUI
import HealthKit
import FastCore
import SevenKit

struct HealthImportScreen: View {
    @Environment(FastModel.self) private var model
    @State private var importing = false
    @State private var status: String?
    var body: some View {
        Form {
            Section {
                Text(f("Import weight measurements you already keep in Apple Health. Access is optional and requested only when you tap Import. Nothing is written to Health."))
                Text(f("This is a manual import, not background sync. Existing samples are skipped when you import again. Health does not tell us whether read permission was denied or no measurements exist.")).textRole(.caption)
                if HKHealthStore.isHealthDataAvailable() {
                    GatedButton(feature: f("Apple Health import"), action: { Task { await importWeight() } }) { Text(f("Import weight from Apple Health")) }
                        .disabled(importing || model.busy)
                } else { Text(f("Apple Health is unavailable on this device.")) }
                if importing { ProgressView() }
                if let status { Text(status).foregroundStyle(Tokens.muted) }
            }
            Section { Text(f("You can review, edit or delete imported measurements in Trends. Manage Health permissions in iPhone Settings.")) }
        }.sevenScreenBackground().navigationTitle(f("Apple Health"))
    }
    private func importWeight() async {
        guard model.store.paidFeaturesAvailable else { return }
        importing = true; defer { importing = false }
        do {
            let health = HKHealthStore()
            let type = HKQuantityType(.bodyMass)
            try await health.requestAuthorization(toShare: [], read: [type])
            let samples: [HKQuantitySample] = try await withCheckedThrowingContinuation { continuation in
                let query = HKSampleQuery(sampleType: type, predicate: nil, limit: 100_001,
                    sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: false)]) { _, samples, error in
                    if let error { continuation.resume(throwing: error) }
                    else { continuation.resume(returning: (samples as? [HKQuantitySample]) ?? []) }
                }
                health.execute(query)
            }
            guard samples.count <= 100_000 else {
                status = f("Apple Health returned more than 100,000 measurements. Nothing was imported.")
                return
            }
            let entries = samples.filter { $0.startDate <= Date() }.map { sample in
                MetricEntry(id: sample.uuid, date: sample.startDate, kind: .weight, value: sample.quantity.doubleValue(for: .gramUnit(with: .kilo)), externalID: "health.weight.\(sample.uuid)")
            }
            let known = Set(model.archive.metrics.compactMap(\.externalID))
            let added = entries.filter { !known.contains($0.externalID!) }
            if added.isEmpty { status = f("No new weight measurements were available."); return }
            guard model.allowNewRecord(existing: false) else { return }
            if await model.change({ archive in
                let existing = Set(archive.metrics.compactMap(\.externalID))
                archive.metrics.append(contentsOf: entries.filter { !existing.contains($0.externalID!) })
            }) { status = f("Imported weight measurements:") + " \(added.count)" }
        } catch { status = f("Apple Health could not be read. You can still enter measurements manually.") }
    }
}
