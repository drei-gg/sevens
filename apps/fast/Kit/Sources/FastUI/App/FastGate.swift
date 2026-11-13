import SwiftUI
import SevenKit

enum FastGate {
    static var unlock: UnlockContent {
        UnlockContent(appName: Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? f("Fast Tracker"),
            features: [f("Fasting and eating timers, with flexible plans"), f("A complete calendar, heatmap and trends"),
                       f("A private journal and optional measurements"), f("Water tracking, personal goals and gentle reminders"), f("Local reminders and portable backups")])
    }
    static var before: BeforeYouBuyContent {
        BeforeYouBuyContent(title: f("Your iPhone already has a timer."), paragraphs: [
            .init(f("Clock can time a fast, Notes can hold a journal, and Health can record your weight. If that’s all you need, keep your money.")),
            .init(f("This brings your timers, calendar, patterns, private journal and water tracker with optional reminders together. Try everything for 7 days, no card. Then {price}, once. Nothing renews."),
                  withoutPrice: f("This brings your timers, calendar, patterns, private journal and water tracker with optional reminders together. Try everything for 7 days, no card. Then a one-time purchase. Nothing renews.")),
            .init(f("Your saved records always stay readable, editable and exportable, even after the trial."))
        ], justScanLabel: f("Look around first"))
    }
    static var expired: TrialEndedContent {
        TrialEndedContent(stillFree: [f("Reading, correcting, deleting and exporting your records"), f("Ending and saving a fast already in progress")],
                          unlockKeeps: [f("Starting new fasts, plans, journal entries and measurements")])
    }
}

extension View {
    public func fastStore(_ model: FastModel) -> some View { environment(model).sevenStore(model.store, unlock: FastGate.unlock) }
}
