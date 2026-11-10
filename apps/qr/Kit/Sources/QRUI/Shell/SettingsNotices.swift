import Foundation

/// The open-source and trademark notices (SPEC §11), one paragraph each.
enum SettingsNotices {
    static func paragraphs() -> [String] {
        [
            String(localized: "settings.notice.psl",
                   defaultValue: "Public Suffix List, from https://publicsuffix.org. Licensed under the Mozilla Public License 2.0 (MPL-2.0).",
                   bundle: .module),
            String(localized: "settings.notice.unicode",
                   defaultValue: "Unicode confusables data, © Unicode, Inc. Used under the Unicode License v3.",
                   bundle: .module),
            String(localized: "settings.notice.denso",
                   defaultValue: "QR Code is a registered trademark of DENSO WAVE INCORPORATED.",
                   bundle: .module),
        ]
    }
}
