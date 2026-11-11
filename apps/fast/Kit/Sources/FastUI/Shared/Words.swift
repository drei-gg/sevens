import Foundation
import FastCore

func f(_ value: String) -> String { String(localized: String.LocalizationValue(value), bundle: .module) }

enum FastFormat {
    static func duration(_ seconds: TimeInterval) -> String {
        let minutes = max(0, Int(seconds / 60))
        return Duration.seconds(minutes * 60).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }
    static func goal(_ minutes: Int?) -> String { minutes.map { duration(Double($0) * 60) } ?? f("Open ended") }
    static func tags(_ text: String) -> [String] {
        Array(Set(text.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })).sorted()
    }
    static func unit(_ kind: MetricKind, settings: TrackerSettings) -> String {
        switch kind {
        case .weight: settings.usePounds ? f("lb") : f("kg")
        case .water: settings.useFluidOunces ? f("US fl oz") : f("mL")
        case .waist: f("cm")
        case .sleep: f("h")
        }
    }
    static func kind(_ kind: MetricKind) -> String {
        switch kind { case .weight: f("Weight"); case .water: f("Water"); case .waist: f("Waist"); case .sleep: f("Sleep") }
    }
    static func error(_ error: any Error) -> String {
        switch error as? TrackerError {
        case .invalidTime: f("The finish must be after the start.")
        case .futureRecord: f("An actual record cannot be in the future. Use Plans for a future fast.")
        case .invalidGoal: f("Enter a positive goal and eating window.")
        case .overlap: f("These times overlap another fast. Correct the times before saving.")
        case .multipleActive: f("A fast is already running. End it before starting another.")
        case .duplicateID, .conflict: f("This backup conflicts with records already here. Nothing was imported.")
        case .unsupportedSchema: f("This file comes from a newer version. Update the app to open it. The original file is untouched.")
        case .unreadable: f("The saved file could not be read. The original is untouched; export it before attempting recovery.")
        case .danglingJournal, .invalidEntry: f("Check the values, dates and journal links. Nothing was saved.")
        case .tooLarge: f("This file is too large to open. The limit is 25 MB or 100,000 records.")
        case .readOnly: f("Records are unavailable. Retry after unlocking the phone, or export the original file.")
        case nil: f("The change could not be saved. Check available storage and try again.")
        }
    }
}
