import SwiftUI
import SevenKit
import ScannerCore

/// A page rendered with its adjustments. Nothing shows while it renders (it takes a frame or two at
/// thumbnail size), so grids don't flicker spinners.
struct PageImage: View {
    let url: URL
    let settings: PageSettings
    var annotations: [PageAnnotation] = []
    var ink: [PageInk] = []
    var maxPixels = 800
    /// Fill the frame (thumbnails) instead of fitting inside it (the editor).
    var fill = false
    var showsLoading = false
    @State private var image: UIImage?
    @State private var failed = false
    var body: some View {
        Group {
            if let image {
                if fill { Image(uiImage: image).resizable().scaledToFill().accessibilityIdentifier("page.imageReady") } else { Image(uiImage: image).resizable().scaledToFit().accessibilityIdentifier("page.imageReady") }
            } else if failed {
                Image(systemName: "exclamationmark.triangle").glyph()
                    .accessibilityLabel(Text("Page unavailable")).accessibilityHidden(false)
            } else if showsLoading { ProgressView("Loading page") } else { Color.clear }
        }
        .transition(.opacity)
        .task(id: ImageTask(url: url, settings: settings, annotations: annotations, ink: ink, pixels: maxPixels)) {
            do {
                try await Task.sleep(for: .milliseconds(100))
                let pixels = maxPixels
                let bytes = try await Task.detached { try DocumentProcessing.rendered(Data(contentsOf: url), settings: settings, maxPixels: pixels, annotations: annotations, ink: ink) }.value
                try Task.checkCancellation()
                withAnimation(.easeOut(duration: 0.2)) { image = UIImage(data: bytes) }; failed = false
            } catch is CancellationError {} catch { failed = true }
        }
    }
    private struct ImageTask: Equatable { var url: URL; var settings: PageSettings; var annotations: [PageAnnotation]; var ink: [PageInk]; var pixels: Int }
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
    @State private var currentPage: ScanPage
    @State private var editText = false
    @State private var editCrop = false
    @State private var annotate = false
    @State private var leaving = false
    init(document: ScanDocument, page: ScanPage, url: URL, number: Int) {
        self.document = document; self.page = page; self.url = url; self.number = number
        _settings = State(initialValue: page.settings)
        _currentPage = State(initialValue: page)
    }
    var body: some View {
        List {
            Section {
                PageImage(url: url, settings: settings, annotations: currentPage.annotations, ink: currentPage.ink, maxPixels: 1600)
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
                Button(settings.crop == nil ? "Crop page" : "Adjust crop") { editCrop = true }.accessibilityIdentifier("page.crop")
                Button(scCount(currentPage.annotations.count + currentPage.ink.count, key: "annotate")) { Task { await openAnnotationEditor() } }.disabled(settings != page.settings).accessibilityIdentifier("page.annotate")
                if settings != page.settings { Text("Save image adjustments before annotating.").textRole(.caption) }
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
                Button("Edit text") { Task { await openTextEditor() } }.disabled(settings != page.settings)
                    .accessibilityIdentifier("page.editText")
                if settings != page.settings { Text("Save image adjustments before editing text.").textRole(.caption) }
                if currentPage.text.isEmpty {
                    Text("No recognized text yet.").textRole(.secondary)
                } else {
                    Text(currentPage.text).textRole(.body).textSelection(.enabled).lineLimit(12)
                    ShareLink(item: currentPage.text) { Label("Share text", systemImage: "square.and.arrow.up") }
                }
            } header: { Text("Recognized text") }
            if let failure { Section { StatusLine(text: failure, color: Tokens.danger) } }
        }
        .sevenScreenBackground()
        .navigationTitle(Text("Page \(number)")).navigationBarTitleDisplayMode(.inline).disabled(working)
        // Unsaved adjustments: a back button that asks first, and no swipe back past it.
        .navigationBarBackButtonHidden(settings != page.settings)
        .toolbar {
            if settings != page.settings {
                ToolbarItem(placement: .topBarLeading) {
                    Button { leaving = true } label: { Label("Back", systemImage: "chevron.backward").labelStyle(.titleAndIcon) }
                        .accessibilityIdentifier("page.back")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save page") { Task { await save() } }.accessibilityIdentifier("page.save").disabled(settings == page.settings)
            }
        }
        .confirmationDialog("Discard page adjustments?", isPresented: $leaving, titleVisibility: .visible) {
            Button("Discard adjustments", role: .destructive) { dismiss() }
            Button("Keep editing", role: .cancel) {}
        }
        .sensoryFeedback(.selection, trigger: settings.quarterTurns)
        .sheet(isPresented: $editCrop) { PageCropEditor(url: url, settings: settings) { settings.crop = $0 } }
        .sheet(isPresented: $annotate) {
            AnnotationEditor(documentID: document.id, original: currentPage, url: url) { currentPage = $0; annotate = false }
        }
        .sheet(isPresented: $editText) {
            TextCorrectionScreen(documentID: document.id, original: currentPage) { updated in
                currentPage = updated; editText = false
            }
        }
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
    private func openAnnotationEditor() async {
        working = true; defer { working = false }
        do {
            let latest = try await model.repository.load(document.id)
            guard let saved = latest.pages.first(where: { $0.id == page.id }) else { throw ScanError.MissingPage }
            currentPage = saved; annotate = true
        } catch { failure = error.localizedDescription }
    }
    private func openTextEditor() async {
        working = true; defer { working = false }
        do {
            let latest = try await model.repository.load(document.id)
            guard let saved = latest.pages.first(where: { $0.id == page.id }) else { throw ScanError.InvalidDocument }
            currentPage = saved
            editText = true
        } catch { failure = error.localizedDescription }
    }
}

private struct TextCorrectionScreen: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let documentID: UUID
    let original: ScanPage
    let saved: (ScanPage) -> Void
    @State private var text: String
    @State private var working = false
    @State private var failure: String?
    @State private var discard = false
    init(documentID: UUID, original: ScanPage, saved: @escaping (ScanPage) -> Void) {
        self.documentID = documentID; self.original = original; self.saved = saved
        _text = State(initialValue: original.text)
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $text).frame(minHeight: 240).accessibilityLabel(Text("Recognized text"))
                        .accessibilityIdentifier("page.correctedText")
                } footer: {
                    Text("Correct the saved text used by search and text sharing. The image stays unchanged. Searchable PDFs recognize the image again; they do not use these corrections.")
                }
                if let failure { Text(failure).foregroundStyle(Tokens.danger) }
            }
            .sevenScreenBackground().disabled(working)
            .navigationTitle("Edit text").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { if text != original.text { discard = true } else { dismiss() } }.disabled(working)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(working || text == original.text)
                        .accessibilityIdentifier("page.saveText")
                }
            }
            .confirmationDialog("Discard text changes?", isPresented: $discard) {
                Button("Discard", role: .destructive) { dismiss() }
            }
        }.interactiveDismissDisabled(working || text != original.text)
    }
    private func save() async {
        guard !working else { return }
        working = true; defer { working = false }
        do {
            let updated = try await model.repository.updatePageText(documentID: documentID, expected: original, text: text)
            await model.reload()
            if let page = updated.pages.first(where: { $0.id == original.id }) { saved(page) }
        } catch { failure = error.localizedDescription + " " + String(localized: "Your text is kept here. If the page changed, close and reopen this editor.") }
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
