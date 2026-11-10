import SevenKit
import SwiftUI

/// A labelled text field with its inline message underneath. The label stays visible (not just
/// a placeholder) and the whole row wraps at the largest text sizes.
struct MakeFormRow: View {
    let title: String
    @Binding var text: String
    let identifier: String
    var issue: MakeFormIssue?
    var keyboard: UIKeyboardType = .default
    var contentType: UITextContentType?
    var capitalization: TextInputAutocapitalization = .never
    var autocorrect = false
    var multiline = false

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            Text(verbatim: title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            TextField(title, text: $text, axis: multiline ? .vertical : .horizontal)
                .lineLimit(multiline ? 1...8 : 1...3)
                .keyboardType(keyboard)
                .textContentType(contentType)
                .textInputAutocapitalization(capitalization)
                .autocorrectionDisabled(!autocorrect)
                .accessibilityIdentifier(identifier)
            if let issue {
                MakeInlineMessage(text: issue.message, isError: true)
            }
        }
    }
}

/// The message under a field: icon plus words, never color alone.
struct MakeInlineMessage: View {
    let text: String
    var isError = true

    var body: some View {
        Label {
            Text(verbatim: text).fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: isError ? "exclamationmark.circle" : "info.circle")
                .foregroundStyle(isError ? .red : .secondary)
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .accessibilityIdentifier(isError ? "make.error" : "make.hint")
    }
}
