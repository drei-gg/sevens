import QRCore
import SevenKit
import SwiftUI
import UIKit

/// PNG, SVG or PDF of the code on screen, handed to the share sheet (Save to Files and Save
/// Image live there; no Photos permission). The caller has already checked the gate.
struct ExportSheet: View {
    let matrix: BitMatrix
    let kind: MakeFormKind
    let symbology: MakeSymbology
    let foreground: RGBColor
    let background: RGBColor
    /// Saves the code to History (once per payload); called on share, copy and Close.
    let onUse: () async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var options = MakeExportOptions()
    @State private var prepared: URL?
    @State private var failed = false
    @State private var copied = false
    @State private var stamp = Date()

    private var fileName: String {
        MakeExportOptions.fileName(symbology: symbology, kind: kind, date: stamp, format: options.format,
                                   sheet: options.pdfLayout == .sheet)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(selection: $options.format) {
                        ForEach(MakeExportFormat.allCases) { Text(verbatim: $0.title).tag($0) }
                    } label: {
                        Text(verbatim: String(localized: "make.export.format", defaultValue: "Format", bundle: .module))
                    }
                    .accessibilityIdentifier("make.export.format")
                }
                switch options.format {
                case .png: pngOptions
                case .svg: svgNote
                case .pdf: pdfOptions
                }
                shareSection
            }
            .scrollContentBackground(.hidden)
            .background(Tokens.paper)
            .navigationTitle(Text(verbatim: String(localized: "make.export.title", defaultValue: "Export", bundle: .module)))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await onUse(); dismiss() }
                    } label: {
                        Text(verbatim: String(localized: "make.export.close", defaultValue: "Close", bundle: .module))
                    }
                    .accessibilityIdentifier("make.export.close")
                }
            }
        }
        .task(id: options) { await prepare() }
    }

    // MARK: Sections

    @ViewBuilder private var pngOptions: some View {
        Section {
            Picker(selection: $options.sizeChoice) {
                ForEach(MakeExportOptions.SizeChoice.allCases, id: \.self) { choice in
                    if let px = choice.preset {
                        Text(verbatim: String(localized: "make.export.px", defaultValue: "\(px) pixels", bundle: .module)).tag(choice)
                    } else {
                        Text(verbatim: String(localized: "make.export.custom", defaultValue: "Custom", bundle: .module)).tag(choice)
                    }
                }
            } label: {
                Text(verbatim: String(localized: "make.export.size", defaultValue: "Size", bundle: .module))
            }
            .accessibilityIdentifier("make.export.size")
            if options.sizeChoice == .custom {
                VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                    Text(verbatim: String(localized: "make.export.customLabel", defaultValue: "Width in pixels (up to 4096)", bundle: .module))
                        .font(.subheadline).foregroundStyle(Tokens.muted).accessibilityHidden(true)
                    TextField(String(localized: "make.export.customLabel", defaultValue: "Width in pixels (up to 4096)", bundle: .module),
                              text: $options.customSize)
                        .keyboardType(.numberPad)
                        .accessibilityIdentifier("make.export.customSize")
                    if options.customSizeWasClamped {
                        let px = options.pixelSize
                        MakeInlineMessage(text: String(localized: "make.export.clamped",
                                                       defaultValue: "Sizes run from \(MakeExportOptions.minPixelSize) to \(PNGRenderer.maxPixelSize). This will be \(px).",
                                                       bundle: .module), isError: false)
                    }
                }
            }
        } footer: {
            Text(verbatim: String(localized: "make.export.pngFooter",
                                  defaultValue: "The image is a whole number of pixels per square, so it can be a little smaller than the size you pick.",
                                  bundle: .module))
        }
    }

    @ViewBuilder private var svgNote: some View {
        Section {
            Text(verbatim: String(localized: "make.export.svgNote",
                                  defaultValue: "A vector file that stays sharp at any size. Good for print and design tools.",
                                  bundle: .module))
        }
    }

    @ViewBuilder private var pdfOptions: some View {
        Section {
            Picker(selection: $options.pdfLayout) {
                Text(verbatim: String(localized: "make.export.single", defaultValue: "One code, centered", bundle: .module)).tag(MakePDFLayout.single)
                Text(verbatim: String(localized: "make.export.sheet", defaultValue: "Sheet of labels", bundle: .module)).tag(MakePDFLayout.sheet)
            } label: {
                Text(verbatim: String(localized: "make.export.layout", defaultValue: "Layout", bundle: .module))
            }
            .accessibilityIdentifier("make.export.layout")
            if options.pdfLayout == .sheet {
                Picker(selection: $options.grid) {
                    ForEach(SheetGrid.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
                } label: {
                    Text(verbatim: String(localized: "make.export.grid", defaultValue: "Labels per page", bundle: .module))
                }
                .accessibilityIdentifier("make.export.grid")
            }
            Picker(selection: $options.paper) {
                ForEach(PaperSize.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
            } label: {
                Text(verbatim: String(localized: "make.export.paper", defaultValue: "Paper", bundle: .module))
            }
            .accessibilityIdentifier("make.export.paper")
            VStack(alignment: .leading, spacing: Tokens.Space.xs) {
                Text(verbatim: String(localized: "make.export.caption", defaultValue: "Caption (optional)", bundle: .module))
                    .font(.subheadline).foregroundStyle(Tokens.muted).accessibilityHidden(true)
                TextField(String(localized: "make.export.caption", defaultValue: "Caption (optional)", bundle: .module),
                          text: $options.caption)
                    .accessibilityIdentifier("make.export.captionField")
            }
        }
    }

    @ViewBuilder private var shareSection: some View {
        Section {
            if let prepared {
                ShareLink(item: prepared) {
                    Label(String(localized: "make.export.share", defaultValue: "Share or save file", bundle: .module),
                          systemImage: "square.and.arrow.up")
                }
                .simultaneousGesture(TapGesture().onEnded { Task { await onUse() } })
                .accessibilityIdentifier("make.export.share")
            } else if failed {
                MakeInlineMessage(text: String(localized: "make.export.failed",
                                               defaultValue: "The file couldn't be created. Try a smaller size.", bundle: .module))
            } else {
                ProgressView().accessibilityLabel(Text(verbatim: String(localized: "make.export.preparing", defaultValue: "Preparing the file", bundle: .module)))
            }
            Button {
                copyImage()
            } label: {
                Label(copied ? String(localized: "make.export.copied", defaultValue: "Copied", bundle: .module)
                             : String(localized: "make.export.copy", defaultValue: "Copy image", bundle: .module),
                      systemImage: copied ? "checkmark" : "doc.on.doc")
            }
            .accessibilityIdentifier("make.export.copy")
        } footer: {
            Text(verbatim: fileName)
        }
    }

    // MARK: Work

    private func prepare() async {
        prepared = nil
        failed = false
        let options = options, matrix = matrix, fg = foreground, bg = background, name = fileName
        let url: URL? = await Task.detached(priority: .userInitiated) {
            let data = MakeExportWriter.data(options: options, matrix: matrix, foreground: fg, background: bg)
            guard !data.isEmpty else { return nil }
            return try? MakeExportWriter.write(data, named: name)
        }.value
        guard !Task.isCancelled else { return }
        prepared = url
        failed = url == nil
    }

    private func copyImage() {
        var png = options
        png.format = .png
        if options.format != .png { png.sizeChoice = .s1024 }
        let data = MakeExportWriter.data(options: png, matrix: matrix, foreground: foreground, background: background)
        guard let image = UIImage(data: data) else { return }
        UIPasteboard.general.image = image
        copied = true
        Task {
            await onUse()
            try? await Task.sleep(for: .seconds(2))
            copied = false
        }
    }
}
