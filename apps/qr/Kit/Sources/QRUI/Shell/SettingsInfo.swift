import Foundation

/// What the Settings screen reads from the app bundle. Everything here is optional: a missing
/// or unfilled value hides its row instead of showing a placeholder.
enum SettingsInfo {
    /// `SevensSupportEmail`; nil when missing, empty or still an unexpanded `$(…)` build setting.
    static func supportEmail(bundle: Bundle = .main) -> String? {
        guard let text = value("SevensSupportEmail", bundle: bundle), text.contains("@") else { return nil }
        return text
    }

    /// `SevensRepositoryURL`, https only.
    static func repositoryURL(bundle: Bundle = .main) -> URL? {
        guard let text = value("SevensRepositoryURL", bundle: bundle),
              let url = URL(string: text), url.scheme?.lowercased() == "https" else { return nil }
        return url
    }

    /// The repository and public keys that newer lists come from and are checked against
    /// (`SevensRepositoryURL`, `SevensListsKeyCurrent`, `SevensListsKeyNext`). Nil when the build has
    /// no usable key, which hides the Check for newer lists button.
    static func listsConfiguration(bundle: Bundle = .main) -> ListsConfiguration? {
        ListsConfiguration(repositoryURL: value("SevensRepositoryURL", bundle: bundle),
                           keys: [value("SevensListsKeyCurrent", bundle: bundle),
                                  value("SevensListsKeyNext", bundle: bundle)])
    }

    /// The text of the bundled `CHANGELOG.md`; nil when it isn't bundled (the row hides).
    static func changelog(bundle: Bundle = .main) -> String? {
        guard let url = bundle.url(forResource: "CHANGELOG", withExtension: "md"),
              let data = try? Data(contentsOf: url), data.count <= 512 * 1024,
              let text = String(data: data, encoding: .utf8) else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func value(_ key: String, bundle: Bundle) -> String? {
        guard let raw = bundle.object(forInfoDictionaryKey: key) as? String else { return nil }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty || text.hasPrefix("$(") ? nil : text
    }
}
