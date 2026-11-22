import Foundation

/// A user-facing English string, looked up in AlarmUI's String Catalog (Resources/Localizable.xcstrings).
/// SwiftUI's own literal lookup reads the app's main bundle, not this package's, so screens pass
/// `f("…")` and plurals use `String(localized:bundle: .module)`, as Fast Tracker does.
func f(_ value: String) -> String { String(localized: String.LocalizationValue(value), bundle: .module) }
