import Foundation

/// Every user-facing string goes through here: `String(localized:defaultValue:bundle:)` with the
/// package bundle (D15). Keys are `seven.…`; `Scripts/gen-strings.py` rebuilds the String Catalog
/// from these call sites.
func seven(_ key: StaticString, _ defaultValue: String.LocalizationValue) -> String {
    String(localized: key, defaultValue: defaultValue, bundle: .module)
}

/// `seven(...)` for strings with one `%@` placeholder.
func seven(_ key: StaticString, _ defaultValue: String.LocalizationValue, _ argument: String) -> String {
    String(format: seven(key, defaultValue), argument)
}
