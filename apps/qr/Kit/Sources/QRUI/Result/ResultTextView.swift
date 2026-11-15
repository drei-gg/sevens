import SwiftUI
import QRCore
import SevenKit

/// Plain text: the raw text, selectable, with a note when it was cut.
struct ResultTextView: View {
    let info: TextInfo

    var body: some View {
        ResultCard(title: String(localized: "result.text.title", defaultValue: "Text in the code", bundle: .module)) {
            Text(info.text.isEmpty ? String(localized: "result.text.empty", defaultValue: "The code holds no text.", bundle: .module) : info.text)
                .textRole(.body)
                .foregroundStyle(.primary)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            if info.isTruncated {
                Text(String(localized: "result.text.truncated", defaultValue: "The code holds more text than this. Only the first part is shown and checked.", bundle: .module))
                    .textRole(.caption)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// A code with a custom scheme, which would hand the rest to another app.
struct ResultAppLinkView: View {
    let info: AppLinkInfo

    var body: some View {
        ResultCard {
            Text(String(localized: "result.appLink.title", defaultValue: "Opens an app", bundle: .module))
                .textRole(.title).foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            ResultField(label: String(localized: "result.appLink.scheme", defaultValue: "Starts with", bundle: .module),
                        value: info.scheme + ":", monospaced: true)
            ResultField(label: String(localized: "result.appLink.whole", defaultValue: "The whole code", bundle: .module),
                        value: info.original, muted: true)
        }
    }
}

/// A code that isn't text.
struct ResultBinaryView: View {
    let byteCount: Int

    var body: some View {
        ResultCard {
            Text(String(localized: "result.binary.title", defaultValue: "Data, not text", bundle: .module))
                .textRole(.title).foregroundStyle(.primary)
            Text(String(localized: "result.binary.count", defaultValue: "\(byteCount) bytes. This app can’t show what is inside.", bundle: .module))
                .textRole(.body).foregroundStyle(Tokens.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
