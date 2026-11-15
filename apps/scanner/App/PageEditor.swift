import SwiftUI
import SevenKit
import ScannerCore

/// A page rendered with its adjustments. Nothing shows while it renders (it takes a frame or two at
/// thumbnail size), so grids don't flicker spinners.
struct PageImage: View {
    let url: URL
    let settings: PageSettings
    var maxPixels = 800
    /// Fill the frame (thumbnails) instead of fitting inside it (the editor).
    var fill = false
    @State private var image: UIImage?
    @State private var failed = false
    var body: some View {
        Group {
            if let image {
                if fill { Image(uiImage: image).resizable().scaledToFill() } else { Image(uiImage: image).resizable().scaledToFit() }
            } else if failed {
                Image(systemName: "exclamationmark.triangle").glyph()
                    .accessibilityLabel(Text("Page unavailable")).accessibilityHidden(false)
            } else { Color.clear }
        }
        .transition(.opacity)
        .task(id: ImageTask(url: url, settings: settings, pixels: maxPixels)) {
            do {
                try await Task.sleep(for: .milliseconds(100))
                let pixels = maxPixels
                let bytes = try await Task.detached { try DocumentProcessing.rendered(Data(contentsOf: url), settings: settings, maxPixels: pixels) }.value
                try Task.checkCancellation()
                withAnimation(.easeOut(duration: 0.2)) { image = UIImage(data: bytes) }; failed = false
            } catch is CancellationError {} catch { failed = true }
        }
    }
    private struct ImageTask: Equatable { var url: URL; var settings: PageSettings; var pixels: Int }
}

/// One page, big, with the few adjustments the app offers under it: rotate, grayscale, brightness
/// and contrast. The page is the hero; the controls sit in one card below.
struct PageEditor: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let document: ScanDocument
    let page: ScanPage
    let url: URL
    let number: Int
    @State private var settings: PageSettings
    @State private var working = false
    @State private var failure: String?
    init(document: ScanDocument, page: ScanPage, url: URL, number: Int) {
        self.document = document; self.page = page; self.url = url; self.number = number
        _settings = State(initialValue: page.settings)
    }
    var body: some View {
        List {
            Section {
                PageImage(url: url, settings: settings, maxPixels: 1600)
                    .frame(maxWidth: .infinity)
                    .frame(height: 420)
                    .padding(.horizontal, Tokens.Space.m)
                    .accessibilityElement()
                    .accessibilityLabel(Text("Page \(number)"))
                    .accessibilityAddTraits(.isImage)
                    .bareListRow()
                AdaptiveStack(spacing: Tokens.Space.s) {
                    Button { settings.quarterTurns = (settings.quarterTurns + 3) % 4 } label: { ChipLabel(title: "Rotate left", systemImage: "rotate.left") }
                        .quickAddChip().accessibilityIdentifier("page.rotateLeft")
                    Button { settings.quarterTurns = (settings.quarterTurns + 1) % 4 } label: { ChipLabel(title: "Rotate right", systemImage: "rotate.right") }
                        .quickAddChip().accessibilityIdentifier("page.rotate")
                }
                .padding(.horizontal, Tokens.Space.m).padding(.top, Tokens.Space.s)
                .bareListRow()
            }
            Section {
                Toggle("Grayscale", isOn: $settings.grayscale).accessibilityIdentifier("page.grayscale")
                AdjustmentSlider(title: "Brightness", value: $settings.brightness, range: -0.5...0.5,
                                 shown: (settings.brightness * 100).formatted(.number.precision(.fractionLength(0)).sign(strategy: .always(includingZero: false))))
                AdjustmentSlider(title: "Contrast", value: $settings.contrast, range: 0.5...2,
                                 shown: settings.contrast.formatted(.percent.precision(.fractionLength(0))))
                Button("Reset to original") { settings = PageSettings() }.disabled(settings == PageSettings())
            } header: { Text("Adjust") } footer: {
                Text("Your original stays untouched. Save to apply these settings to previews, recognition and exports.").textRole(.caption)
            }
            Section {
                if page.text.isEmpty {
                    Text("No recognized text yet.").textRole(.secondary)
                } else {
                    Text(page.text).textRole(.body).textSelection(.enabled).lineLimit(12)
                    ShareLink(item: page.text) { Label("Share text", systemImage: "square.and.arrow.up") }
                }
            } header: { Text("Recognized text") }
            if let failure { Section { StatusLine(text: failure, color: Tokens.danger) } }
        }
        .sevenScreenBackground()
        .navigationTitle(Text("Page \(number)")).navigationBarTitleDisplayMode(.inline).disabled(working)
        .toolbar { Button("Save page") { Task { await save() } }.accessibilityIdentifier("page.save").disabled(settings == page.settings) }
        .sensoryFeedback(.selection, trigger: settings.quarterTurns)
    }
    private func save() async {
        working = true; defer { working = false }
        do {
            let updated = try await model.repository.updatePage(documentID: document.id, pageID: page.id, settings: settings)
            await model.reload(); dismiss()
            var selection = updated; selection.pages = updated.pages.filter { $0.id == page.id }
            await model.recognize(selection)
        } catch { failure = error.localizedDescription }
    }
}

/// A slider with its name and current value on one line above it.
private struct AdjustmentSlider: View {
    let title: LocalizedStringKey
    @Binding var value: Double
    let range: ClosedRange<Double>
    let shown: String
    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            HStack {
                Text(title).textRole(.body)
                Spacer()
                Text(shown).textRole(.secondary).numeric()
            }
            Slider(value: $value, in: range, step: 0.05).accessibilityLabel(Text(title)).accessibilityValue(Text(shown))
        }
    }
}
