import SwiftUI
import ScannerCore

struct PageImage: View {
    let url: URL
    let settings: PageSettings
    var maxPixels = 800
    @State private var image: UIImage?
    @State private var failed = false
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFit() }
            else if failed { Label("Page unavailable", systemImage: "exclamationmark.triangle") }
            else { ProgressView() }
        }.task(id: ImageTask(url: url, settings: settings, pixels: maxPixels)) {
            do {
                try await Task.sleep(for: .milliseconds(100))
                let pixels = maxPixels
                let bytes = try await Task.detached { try DocumentProcessing.rendered(Data(contentsOf: url), settings: settings, maxPixels: pixels) }.value
                try Task.checkCancellation(); image = UIImage(data: bytes); failed = false
            } catch is CancellationError {} catch { failed = true }
        }
    }
    private struct ImageTask: Equatable { var url: URL; var settings: PageSettings; var pixels: Int }
}
struct PageEditor: View {
    @Environment(ScannerModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let document: ScanDocument
    let page: ScanPage
    let url: URL
    @State private var settings: PageSettings
    @State private var working = false
    @State private var failure: String?
    init(document: ScanDocument, page: ScanPage, url: URL) {
        self.document = document; self.page = page; self.url = url
        _settings = State(initialValue: page.settings)
    }
    var body: some View {
        Form {
            Section { PageImage(url: url, settings: settings, maxPixels: 1200).frame(maxWidth: .infinity).frame(height: 300) }
            Section("Adjust page") {
                Button("Rotate right", systemImage: "rotate.right") { settings.quarterTurns = (settings.quarterTurns + 1) % 4 }.accessibilityIdentifier("page.rotate")
                Toggle("Grayscale", isOn: $settings.grayscale).accessibilityIdentifier("page.grayscale")
                VStack(alignment: .leading) { Text("Brightness"); Slider(value: $settings.brightness, in: -0.5...0.5, step: 0.05).accessibilityLabel("Brightness") }
                VStack(alignment: .leading) { Text("Contrast"); Slider(value: $settings.contrast, in: 0.5...2, step: 0.05).accessibilityLabel("Contrast") }
                Button("Reset to original") { settings = PageSettings() }
                Text("Your original stays untouched. Save to apply these settings to previews, recognition and exports.").font(.footnote).foregroundStyle(.secondary)
            }
            Section("Recognized text") {
                Text(page.text.isEmpty ? "No recognized text yet." : page.text).textSelection(.enabled)
                if !page.text.isEmpty { ShareLink("Share text", item: page.text) }
            }
            if let failure { Text(failure).foregroundStyle(.red) }
        }.navigationTitle("Edit page").navigationBarTitleDisplayMode(.inline).disabled(working)
            .toolbar { Button("Save page") { Task { await save() } }.accessibilityIdentifier("page.save") }
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
