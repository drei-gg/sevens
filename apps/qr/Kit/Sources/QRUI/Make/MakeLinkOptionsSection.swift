import SevenKit
import SwiftUI

/// Identifiers for the link options (SPEC §8). Kept here so Make owns them.
enum MakeLinkAccessibilityID {
    static let smallerCodeSwitch = "make.link.smaller"
    static let trackingPrompt = "make.link.tracking"
    static let trackingPreview = "make.link.tracking.preview"
    static let trackingRemove = "make.link.tracking.remove"
}

/// Under the link field: "Same link, smaller code" when capitals shrink the code, and the offer to
/// remove tracking tags. Nothing here changes the link unless the person taps or flips it.
struct MakeLinkOptionsSection: View {
    @Bindable var model: MakeScreenModel

    var body: some View {
        if model.kind == .link, model.smallerCodeAvailable || !model.trackingTags.isEmpty {
            Section {
                if model.smallerCodeAvailable {
                    Toggle(isOn: $model.useSmallerCode) {
                        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                            Text(verbatim: String(localized: "make.link.smaller", defaultValue: "Same link, smaller code", bundle: .module))
                            Text(verbatim: String(localized: "make.link.smaller.detail",
                                                  defaultValue: "The web address is written in capitals, which fit a smaller code. It opens the same place.",
                                                  bundle: .module))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier(MakeLinkAccessibilityID.smallerCodeSwitch)
                }
                if let shorter = model.trackingFreeLink, !model.trackingTags.isEmpty {
                    VStack(alignment: .leading, spacing: Tokens.Space.s) {
                        Text(verbatim: String(localized: "make.link.tracking",
                                              defaultValue: "This link carries tracking tags. Remove them?", bundle: .module))
                            .accessibilityIdentifier(MakeLinkAccessibilityID.trackingPrompt)
                        Text(verbatim: model.trackingTags.joined(separator: ", "))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Text(verbatim: shorter)
                            .font(.footnote.monospaced())
                            .textSelection(.enabled)
                            .accessibilityIdentifier(MakeLinkAccessibilityID.trackingPreview)
                        Button {
                            model.removeTrackingTags()
                        } label: {
                            Text(verbatim: String(localized: "make.link.tracking.remove", defaultValue: "Remove tracking tags", bundle: .module))
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier(MakeLinkAccessibilityID.trackingRemove)
                    }
                }
            }
        }
    }
}
