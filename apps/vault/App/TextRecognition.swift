import Foundation
import ImageIO
import Vision
import SwiftUI
import VaultCore
import PDFKit
import UIKit

enum VaultTextRecognition {
    nonisolated static func read(_ url: URL) throws -> String {
        try Task.checkCancellation()
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 2400,
                kCGImageSourceCreateThumbnailWithTransform: true
              ] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
        return try recognize(image)
    }
    nonisolated private static func recognize(_ image: CGImage) throws -> String {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        try VNImageRequestHandler(cgImage: image).perform([request])
        try Task.checkCancellation()
        return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
    }
    nonisolated static func pageCount(_ url: URL) throws -> UInt32 {
        guard let document = PDFDocument(url: url), !document.isLocked, document.pageCount > 0 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return UInt32(clamping: document.pageCount)
    }
    nonisolated static func readPDF(_ url: URL, first: UInt32, last: UInt32) throws -> String {
        guard let document = PDFDocument(url: url), !document.isLocked else { throw CocoaError(.fileReadCorruptFile) }
        let plan = recognitionPlan(pageCount: UInt32(clamping: document.pageCount), firstPage: first, lastPage: last)
        guard plan.issue == nil else { throw CocoaError(.validationMissingMandatoryProperty) }
        var result = ""
        for index in plan.pages {
            try Task.checkCancellation()
            let text = try autoreleasepool {
                guard let page = document.page(at: Int(index)) else { throw CocoaError(.fileReadCorruptFile) }
                let bounds = page.bounds(for: .cropBox)
                let size = try recognitionPageSize(width: bounds.width, height: bounds.height)
                let pixels = CGSize(width: Int(size.width), height: Int(size.height))
                let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
                let image = UIGraphicsImageRenderer(size: pixels, format: format).image { context in
                    UIColor.white.setFill(); context.fill(CGRect(origin: .zero, size: pixels))
                    context.cgContext.translateBy(x: 0, y: pixels.height)
                    context.cgContext.scaleBy(x: pixels.width / bounds.width, y: -pixels.height / bounds.height)
                    context.cgContext.translateBy(x: -bounds.minX, y: -bounds.minY)
                    page.draw(with: .cropBox, to: context.cgContext)
                }
                guard let cgImage = image.cgImage else { throw CocoaError(.fileReadCorruptFile) }
                return try recognize(cgImage)
            }
            result = try recognitionAppend(current: result, pageNumber: index + 1, text: text)
        }
        try Task.checkCancellation()
        return result
    }

}

struct RecognizedTextScreen: View {
    @Environment(VaultModel.self) private var model
    let draft: RecognizedImageText
    @State private var name: String
    @State private var text: String
    @State private var discard = false
    @State private var saving = false
    init(draft: RecognizedImageText) {
        self.draft = draft
        _name = State(initialValue: RecognizedTextScreen.defaultName(draft.item))
        _text = State(initialValue: draft.text)
    }
    private var hasChanges: Bool { text != draft.text || name != Self.defaultName(draft.item) }
    static func defaultName(_ item: VaultItem) -> String {
        String(localized: "\((item.name as NSString).deletingPathExtension) text", comment: "Default name for a note of recognized text; the placeholder is the source file name without extension.")
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Note name") { TextField("Note name", text: $name).accessibilityIdentifier("ocr-name") }
                    TextEditor(text: $text).frame(minHeight: 240).accessibilityLabel("Recognized text")
                        .accessibilityIdentifier("ocr-text")
                } footer: {
                    Text("Recognition runs on this device. Check the result before saving a separate encrypted note. The original file stays unchanged.")
                }
                if draft.text.isEmpty { Text("No text was recognized. You can enter a note here or close and try another file.") }
                if !model.store.paidFeaturesAvailable { Text("Start the trial or unlock to save new notes. You can still read and copy this text.") }
            }
            .disabled(model.busy)
            .navigationTitle("Review").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { if hasChanges { discard = true } else { model.cleanPresentation() } }.disabled(model.busy)
                }
                ToolbarItem(placement: .primaryAction) { Button("Lock") { model.lock() }.accessibilityIdentifier("ocr-lock") }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save note") {
                        guard model.store.paidFeaturesAvailable, !saving, !model.busy else { return }
                        saving = true
                        let name = name, text = text
                        let generation = model.generation
                        Task {
                            defer { saving = false }
                            await model.update { try await model.repository.addNote(name: name, text: text, folder: draft.item.folder) }
                            if generation == model.generation, model.error == nil { model.cleanPresentation() }
                        }
                    }.disabled(model.busy || saving || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || text.isEmpty || !model.store.paidFeaturesAvailable)
                        .accessibilityIdentifier("ocr-save")
                }
            }
            .confirmationDialog("Discard text changes?", isPresented: $discard) {
                Button("Discard", role: .destructive) { model.cleanPresentation() }
                Button("Keep editing") { discard = false }
            }
        }.interactiveDismissDisabled(model.busy || hasChanges)
    }
}


struct PdfRangeScreen: View {
    @Environment(VaultModel.self) private var model
    let draft: PdfRecognitionRange
    @State private var first = "1"
    @State private var last: String
    init(draft: PdfRecognitionRange) { self.draft = draft; _last = State(initialValue: String(min(draft.pages, 20))) }
    private var plan: RecognitionPlan { recognitionPlan(pageCount: draft.pages, firstPage: UInt32(first) ?? 0, lastPage: UInt32(last) ?? 0) }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("First page") { TextField("First page", text: $first).keyboardType(.numberPad).multilineTextAlignment(.trailing).accessibilityIdentifier("ocr-first") }
                    LabeledContent("Last page") { TextField("Last page", text: $last).keyboardType(.numberPad).multilineTextAlignment(.trailing).accessibilityIdentifier("ocr-last") }
                } header: { Text("\(Int(draft.pages)) pages") } footer: {
                    if let issue = plan.issue { Text(issue.message) }
                    else { Text("Choose up to \(Int(plan.maximumPages)) pages. Review the result before saving a separate encrypted note. The original PDF stays unchanged.") }
                }
                if model.busy { ProgressView("Recognizing selected pages…") }
            }
            .disabled(model.busy)
            .navigationTitle("PDF pages").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { model.cleanPresentation() }.disabled(model.busy) }
                ToolbarItem(placement: .primaryAction) { Button("Lock") { model.lock() }.accessibilityIdentifier("ocr-lock") }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Recognize") {
                        let first = UInt32(first) ?? 0, last = UInt32(last) ?? 0
                        Task { await model.recognizePDF(draft, first: first, last: last) }
                    }.disabled(model.busy || plan.issue != nil).accessibilityIdentifier("ocr-recognize")
                }
            }
        }.interactiveDismissDisabled(model.busy)
    }
}
