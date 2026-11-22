import SwiftUI
import UIKit
import CropViewController
import ScannerCore
import SevenKit

/// Native crop handles plus exact edge entry. Only settings are returned; no original is replaced.
struct PageCropEditor: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL
    let settings: PageSettings
    let apply: (PageCrop?) -> Void
    @State private var left: String
    @State private var top: String
    @State private var right: String
    @State private var bottom: String
    @State private var image: UIImage?
    @State private var handles = false
    @State private var discard = false
    @State private var failure: String?
    private let initial: PageCrop
    private static let whole = PageCrop(left: 0, top: 0, right: 1, bottom: 1)
    init(url: URL, settings: PageSettings, apply: @escaping (PageCrop?) -> Void) {
        self.url = url; self.settings = settings; self.apply = apply
        let rect = (try? pageCropRotated(crop: settings.crop ?? Self.whole, quarterTurns: UInt32(settings.quarterTurns))) ?? Self.whole
        initial = rect
        _left = State(initialValue: Self.percent(rect.left)); _top = State(initialValue: Self.percent(rect.top))
        _right = State(initialValue: Self.percent(rect.right)); _bottom = State(initialValue: Self.percent(rect.bottom))
    }
    private static func percent(_ value: Double) -> String { String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), value * 100) }
    private var displayed: PageCrop? {
        guard let l = Double(left.replacingOccurrences(of: ",", with: ".")), let t = Double(top.replacingOccurrences(of: ",", with: ".")),
              let r = Double(right.replacingOccurrences(of: ",", with: ".")), let b = Double(bottom.replacingOccurrences(of: ",", with: ".")) else { return nil }
        let crop = PageCrop(left: l / 100, top: t / 100, right: r / 100, bottom: b / 100)
        return (try? pageCropPixels(crop: crop, width: 1, height: 1)) == nil ? nil : crop
    }
    private var original: PageCrop? {
        guard let displayed else { return nil }
        return try? pageCropRotated(crop: displayed, quarterTurns: UInt32(4 - settings.quarterTurns))
    }
    private var dirty: Bool { [left, top, right, bottom] != [initial.left, initial.top, initial.right, initial.bottom].map(Self.percent) }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    var preview = settings
                    let _ = preview.crop = original
                    PageImage(url: url, settings: preview, showsLoading: true).frame(height: 260).frame(maxWidth: .infinity)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text("Crop preview"))
                        .accessibilityValue(Text(original == nil ? "Enter valid bounds to preview the crop." : "The page as it will look with these bounds."))
                        .accessibilityAddTraits(.isImage)
                    Button("Adjust with handles") { handles = true }.disabled(image == nil)
                        .accessibilityIdentifier("page.cropHandles")
                } footer: { Text("Your original stays intact. Save the page to update recognition and exports.") }
                Section("Bounds (%)") {
                    edge("Left", text: $left); edge("Top", text: $top)
                    edge("Right", text: $right); edge("Bottom", text: $bottom)
                    if displayed == nil { Text("Enter bounds from 0 to 100 with right greater than left and bottom greater than top.").foregroundStyle(Tokens.danger) }
                    Button("Use full page") { set(Self.whole) }.accessibilityIdentifier("page.cropReset")
                }
                if let failure { Text(failure).foregroundStyle(Tokens.danger) }
            }
            .sevenScreenBackground().navigationTitle("Crop page").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { if dirty { discard = true } else { dismiss() } } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use crop") { if let original { apply(original == Self.whole ? nil : original); dismiss() } }
                        .disabled(original == nil).accessibilityIdentifier("page.useCrop")
                }
            }
            .confirmationDialog("Discard crop changes?", isPresented: $discard) { Button("Discard", role: .destructive) { dismiss() } }
            .fullScreenCover(isPresented: $handles) {
                if let image {
                    NativePageCrop(image: image, initial: displayed ?? initial, cancel: { handles = false }, apply: { set($0); handles = false })
                        .ignoresSafeArea()
                }
            }
            .task {
                do {
                    var uncropped = settings; uncropped.crop = nil
                    let renderSettings = uncropped
                    let data = try await Task.detached { try DocumentProcessing.rendered(Data(contentsOf: url), settings: renderSettings, maxPixels: 1600) }.value
                    try Task.checkCancellation(); image = UIImage(data: data)
                } catch is CancellationError {} catch { failure = error.localizedDescription }
            }
        }.interactiveDismissDisabled(dirty)
    }
    private func edge(_ title: String, text: Binding<String>) -> some View {
        LabeledContent(sc(title)) {
            TextField("Percent", text: text).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                .accessibilityLabel(String(localized: "\(sc(title)) percent")).accessibilityIdentifier("page.crop" + title)
        }
    }
    private func set(_ crop: PageCrop) {
        left = Self.percent(crop.left); top = Self.percent(crop.top); right = Self.percent(crop.right); bottom = Self.percent(crop.bottom)
    }
}

private struct NativePageCrop: UIViewControllerRepresentable {
    let image: UIImage
    let initial: PageCrop
    let cancel: () -> Void
    let apply: (PageCrop) -> Void
    func makeUIViewController(context: Context) -> CropViewController {
        let controller = CropViewController(image: image)
        controller.rotateButtonsHidden = true
        controller.rotateClockwiseButtonHidden = true
        controller.aspectRatioPickerButtonHidden = true
        controller.doneButtonTitle = sc("Use bounds")
        controller.loadViewIfNeeded()
        // iOS 26 uses icon-only crop toolbars; give the checkmark an actionable VoiceOver label.
        controller.toolbar.doneIconButton.accessibilityLabel = sc("Use bounds")
        controller.toolbar.doneIconButton.accessibilityIdentifier = "page.cropUseBounds"
        controller.toolbar.doneTextButton?.accessibilityIdentifier = "page.cropUseBounds"
        controller.toolbar.cancelIconButton.accessibilityLabel = sc("Cancel crop")
        controller.imageCropFrame = CGRect(x: initial.left * image.size.width, y: initial.top * image.size.height,
            width: (initial.right - initial.left) * image.size.width, height: (initial.bottom - initial.top) * image.size.height)
        controller.onDidFinishCancelled = { _ in cancel() }
        controller.onDidCropImageToRect = { rect, _ in
            apply(PageCrop(left: rect.minX / image.size.width, top: rect.minY / image.size.height,
                right: rect.maxX / image.size.width, bottom: rect.maxY / image.size.height))
        }
        return controller
    }
    func updateUIViewController(_ controller: CropViewController, context: Context) {}
}
