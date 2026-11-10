import Foundation
import Observation

/// The app's own settings (PLAN §6.12), kept in `UserDefaults`.
///
/// `keepHistory` is the "Keep history" toggle, on by default. Turning it off stops *new* saving;
/// it does not delete what is already there ("Clear history" does that, with a confirmation).
@Observable
@MainActor
public final class AppSettings {
    public static let keepHistoryKey = "qr.settings.keepHistory"

    public var keepHistory: Bool {
        didSet { defaults.set(keepHistory, forKey: Self.keepHistoryKey) }
    }

    @ObservationIgnored private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        keepHistory = defaults.object(forKey: Self.keepHistoryKey) as? Bool ?? true
    }
}
