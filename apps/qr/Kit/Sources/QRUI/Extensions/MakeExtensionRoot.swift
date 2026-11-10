import QRCore
import SevenKit
import SevenKitCore
import SwiftUI
import UIKit

/// The Make QR code share extension (SPEC §9). Gated: a trial or unlock is needed (D2).
/// Builds the payload from the shared items, shows the code full width with brightness raised,
/// and offers Save image, Copy and Done. Writes no History (D13); no purchase happens here.
public struct MakeExtensionRoot: View {
    let context: NSExtensionContext?

    @State private var phase: Phase = .loading
    @State private var copied = false
    private var preset = false

    enum Phase {
        case loading, locked, unsupported, failed(String), ready(ExtensionMadeCode)
    }

    public init(context: NSExtensionContext?) {
        self.context = context
    }

    /// Previews: show a fixed phase without reading any input or the entitlement.
    init(previewPhase: Phase) {
        self.context = nil
        self.preset = true
        _phase = State(initialValue: previewPhase)
    }

    public var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Tokens.paper.ignoresSafeArea())
                .navigationTitle(String(localized: "ext.make.title", defaultValue: "Make QR code", bundle: .module))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(String(localized: "common.done", defaultValue: "Done", bundle: .module)) { finish() }
                    }
                }
        }
        .task { await prepare() }
    }

    @ViewBuilder private var content: some View {
        switch phase {
        case .loading:
            ProgressView().accessibilityLabel(String(localized: "ext.make.loading", defaultValue: "Preparing your code", bundle: .module))
        case .locked:
            message(String(localized: "ext.make.locked",
                           defaultValue: "Make QR code is part of the unlock. Open the app to see what it adds.",
                           bundle: .module))
        case .unsupported:
            message(String(localized: "ext.make.unsupported", defaultValue: "Can't make a code from this file.", bundle: .module))
        case .failed(let text):
            message(text)
        case .ready(let made):
            ScrollView {
                VStack(spacing: Tokens.Space.m) {
                    Image(uiImage: made.image)
                        .resizable()
                        .interpolation(.none)
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel(String(localized: "ext.make.code.a11y", defaultValue: "QR code", bundle: .module))
                    actions(made)
                }
                .padding(Tokens.Space.m)
            }
            .background(ExtensionBrightness())
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(Tokens.ink)
            .multilineTextAlignment(.leading)
            .padding(Tokens.Space.l)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func actions(_ made: ExtensionMadeCode) -> some View {
        VStack(spacing: Tokens.Space.s) {
            if let url = made.fileURL {
                ShareLink(item: url, preview: SharePreview(String(localized: "ext.make.share.preview", defaultValue: "QR code", bundle: .module), image: Image(uiImage: made.image))) {
                    Label(String(localized: "ext.make.save", defaultValue: "Save image", bundle: .module), systemImage: "square.and.arrow.down")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
            Button {
                UIPasteboard.general.image = made.image
                copied = true
            } label: {
                Label(copied ? String(localized: "ext.copied", defaultValue: "Copied", bundle: .module)
                             : String(localized: "ext.make.copy", defaultValue: "Copy", bundle: .module),
                      systemImage: copied ? "checkmark" : "doc.on.doc")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .controlSize(.large)
        .tint(Tokens.red)
    }

    private func finish() {
        if case .unsupported = phase { ExtensionDismiss.cancel(context) } else { ExtensionDismiss.done(context) }
    }

    @MainActor
    private func prepare() async {
        if preset { return }
        guard await Self.isUnlocked() else { phase = .locked; return }
        let source = await ExtensionMakeLoader.load(from: context)
        switch ExtensionMakeInput.decide(source) {
        case .unsupported:
            phase = .unsupported
        case .request(let request):
            do {
                if let made = try ExtensionMadeCode(text: PayloadEncoder.encode(request)) {
                    phase = .ready(made)
                } else {
                    phase = .failed(MakeError.generatorFailed.message)
                }
            } catch let error as MakeError {
                phase = .failed(error.message)
            } catch {
                phase = .failed(MakeError.generatorFailed.message)
            }
        }
    }

    /// Snapshot, then StoreKit (C7). Without a readable config: unlocked in Debug only.
    @MainActor
    static func isUnlocked() async -> Bool {
        guard let config = SevenConfig(bundle: .main) else {
            #if DEBUG
            return true
            #else
            return false
            #endif
        }
        return await ExtensionEntitlement.currentState(config: config).paidFeaturesAvailable
    }
}

#if DEBUG
#Preview("Make extension, code, light") {
    MakeExtensionRoot(previewPhase: previewPhase()).preferredColorScheme(.light)
}

#Preview("Make extension, code, dark") {
    MakeExtensionRoot(previewPhase: previewPhase()).preferredColorScheme(.dark)
}

#Preview("Make extension, locked, accessibility5") {
    MakeExtensionRoot(previewPhase: .locked).dynamicTypeSize(.accessibility5)
}

#Preview("Make extension, unsupported") {
    MakeExtensionRoot(previewPhase: .unsupported)
}

@MainActor
private func previewPhase() -> MakeExtensionRoot.Phase {
    if let made = try? ExtensionMadeCode(text: "https://example.com/menu") { return .ready(made) }
    return .failed("Preview failed")
}
#endif
