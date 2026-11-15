import Observation
import QRCore
import SwiftUI
import UIKit

/// State of the Make screen: the chosen kind and form, the code options, the colors, and the
/// last generated code. The view re-runs `refresh()` (debounced) whenever `inputKey` changes.
@Observable
@MainActor
final class MakeScreenModel {
    /// Everything that changes the picture. Used as the `.task(id:)` of the debounce.
    struct InputKey: Hashable {
        var kind: MakeFormKind
        var form: MakeFormState
        var symbology: MakeSymbology
        var correction: Correction
        var foreground: RGBColor
        var background: RGBColor
        /// The "Same link, smaller code" switch (SPEC §8).
        var useSmallerCode: Bool
    }

    struct Generated: Sendable {
        var matrix: BitMatrix
        var png: Data
        /// The text actually encoded.
        var text: String
        var typedText: String
        /// The capitalized link makes a smaller QR version than the typed one (SPEC §8).
        var smallerCodeAvailable: Bool
        var trackingTags: [String]
        var trackingFree: String?
    }

    var kind: MakeFormKind
    var form: MakeFormState
    var symbology = MakeSymbology.qr
    var correction = Correction.auto
    var foreground = Color.black
    var background = Color.white
    /// "Same link, smaller code": on by default; only offered when it really shrinks the code.
    var useSmallerCode = true
    /// Tracking tags to match; nil means the bundled list. Tests inject their own.
    var trackingList: [TrackingParam]?

    private(set) var matrix: BitMatrix?
    private(set) var previewImage: UIImage?
    private(set) var payloadText: String?
    private(set) var generationError: MakeError?
    private(set) var generatedKey: InputKey?
    /// What the person typed, when `payloadText` (what is encoded) differs or not.
    private(set) var typedText: String?
    private(set) var smallerCodeAvailable = false
    private(set) var trackingTags: [String] = []
    /// The link without its tracking tags, for the preview and the button.
    private(set) var trackingFreeLink: String?
    private(set) var recorded: Set<String> = []

    /// Debounce before regenerating while the person types.
    static let debounce: Duration = .milliseconds(200)

    init(prefill: MakeRequest? = nil) {
        if let prefill {
            kind = MakeFormKind(request: prefill)
            form = MakeFormState(prefill: prefill)
        } else {
            kind = .link
            form = MakeFormState()
        }
    }

    var foregroundRGB: RGBColor { MakeStyle.rgb(foreground) }
    var backgroundRGB: RGBColor { MakeStyle.rgb(background) }

    var inputKey: InputKey {
        InputKey(kind: kind, form: form, symbology: symbology, correction: correction,
                 foreground: foregroundRGB, background: backgroundRGB, useSmallerCode: useSmallerCode)
    }

    var outcome: MakeFormState.Outcome { form.result(for: kind) }

    /// The form problem to show under a field, if any (blank required fields are prompts, see `prompt`).
    func issue(for field: MakeFormField) -> MakeFormIssue? {
        if case .invalid(let issue) = outcome, issue.field == field, !issue.isBlank { return issue }
        return nil
    }

    /// Nothing typed yet.
    var isBlank: Bool {
        if case .invalid(let issue) = outcome { return issue.isBlank }
        return false
    }

    /// The code on screen matches what is typed now, so Export may run.
    var canExport: Bool { matrix != nil && generationError == nil && generatedKey == inputKey }

    var contrastWarnings: [ContrastWarning] {
        Contrast.warnings(foreground: foregroundRGB, background: backgroundRGB)
    }

    /// Generates the code for the current input. Call after the debounce sleep.
    func refresh() async {
        let key = inputKey
        guard case .request(let request) = outcome else {
            matrix = nil
            previewImage = nil
            payloadText = nil
            typedText = nil
            smallerCodeAvailable = false
            trackingTags = []
            trackingFreeLink = nil
            generationError = nil
            generatedKey = key
            return
        }
        let typed = PayloadEncoder.encode(request)
        let symbology = symbology, correction = correction
        let fg = key.foreground, bg = key.background
        let isLink = kind == .link, useSmaller = useSmallerCode, list = trackingList
        let result: Result<Generated, MakeError> = await Task.detached(priority: .userInitiated) {
            do {
                var m = try CodeGenerator.generate(text: typed, symbology: symbology, correction: correction)
                var text = typed
                var smaller = false
                if isLink, symbology == .qr, let compact = LinkCompactor.compacted(typed), compact != typed,
                   let c = try? CodeGenerator.generate(text: compact, symbology: symbology, correction: correction),
                   let before = CodeGenerator.qrVersion(of: m), let after = CodeGenerator.qrVersion(of: c), after < before {
                    smaller = true
                    if useSmaller { m = c; text = compact }
                }
                var tags: [String] = [], free: String?
                if isLink {
                    let params = list ?? BundledData.shared.trackingParams
                    tags = LinkCompactor.trackingParameters(in: typed, list: params)
                    free = LinkCompactor.removingTracking(from: typed, list: params)
                }
                let png = PNGRenderer.png(matrix: m, pixelSize: 512, foreground: fg, background: bg)
                return .success(Generated(matrix: m, png: png, text: text, typedText: typed,
                                          smallerCodeAvailable: smaller, trackingTags: tags, trackingFree: free))
            } catch let error as MakeError {
                return .failure(error)
            } catch {
                return .failure(.generatorFailed)
            }
        }.value
        guard !Task.isCancelled else { return }
        generatedKey = key
        typedText = typed
        switch result {
        case .success(let g):
            payloadText = g.text
            smallerCodeAvailable = g.smallerCodeAvailable
            trackingTags = g.trackingTags
            trackingFreeLink = g.trackingFree
            matrix = g.matrix
            previewImage = UIImage(data: g.png)
            generationError = nil
        case .failure(let error):
            payloadText = typed
            smallerCodeAvailable = false
            trackingTags = []
            trackingFreeLink = nil
            matrix = nil
            previewImage = nil
            generationError = error
        }
    }

    /// "QR code for https://example.org/menu", the VoiceOver label of the picture.
    var codeAccessibilityLabel: String {
        symbology.accessibilityLabel(for: typedText ?? payloadText ?? "")
    }

    /// "Remove them": puts the link without its tracking tags into the field. Never automatic.
    func removeTrackingTags() {
        guard kind == .link, let free = trackingFreeLink else { return }
        form.link = free
    }

    /// Saves the current code to History once per distinct payload (and symbology). No-op unless
    /// the code on screen is current. `AppModel.record` itself respects Keep history and the lock.
    func recordUse(in app: AppModel) async {
        guard canExport, let text = payloadText else { return }
        let token = symbology.rawValue + "\u{1F}" + text
        guard !recorded.contains(token) else { return }
        recorded.insert(token)
        await app.record(code: ScannedCode(text: text, symbology: symbology.symbology), kind: .made)
    }
}
