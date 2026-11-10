import QRCore
import SevenKit
import SwiftUI

/// Make a code from one of nine kinds of content, see it live, style it, export it (SPEC §8).
/// The caller gates this screen (Make and export are paid); it doesn't gate again.
public struct MakeScreen: View {
    let prefill: MakeRequest?

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var model: MakeScreenModel
    @State private var showExport = false

    /// The Make screen; `prefill` comes from a result's "Make code" action. Callers wrap it in
    /// `.gated(feature:)` (paid). Pushed inside a NavigationStack.
    public init(prefill: MakeRequest? = nil) {
        self.prefill = prefill
        _model = State(initialValue: MakeScreenModel(prefill: prefill))
    }

    public var body: some View {
        Form {
            typeSection
            Section {
                MakeFormFields(model: model)
                if let error = model.generationError {
                    MakeInlineMessage(text: error.message)
                }
            } header: {
                Text(verbatim: model.kind.title)
                    .foregroundStyle(Tokens.muted)
            }
            previewSection
            codeTypeSection
            MakeStyleSection(foreground: $model.foreground, background: $model.background,
                             warnings: model.contrastWarnings)
        }
        .scrollContentBackground(.hidden)
        .background(Tokens.paper)
        .navigationTitle(Text(verbatim: String(localized: "make.title", defaultValue: "Make a code", bundle: .module)))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    Task {
                        await model.recordUse(in: app)
                        dismiss()
                    }
                } label: {
                    Text(verbatim: String(localized: "make.done", defaultValue: "Done", bundle: .module))
                }
                .accessibilityIdentifier(QRAccessibilityID.makeDone)
            }
        }
        .task(id: model.inputKey) {
            try? await Task.sleep(for: MakeScreenModel.debounce)
            guard !Task.isCancelled else { return }
            await model.refresh()
        }
        .sheet(isPresented: $showExport) {
            if let matrix = model.matrix {
                ExportSheet(matrix: matrix, kind: model.kind, symbology: model.symbology,
                            foreground: model.foregroundRGB, background: model.backgroundRGB) {
                    await model.recordUse(in: app)
                }
                .presentationDetents(typeSize.isAccessibilitySize ? [.large] : [.medium, .large])
            }
        }
    }

    // MARK: Sections

    private var typeSection: some View {
        Section {
            Picker(selection: $model.kind) {
                ForEach(MakeFormKind.allCases) { kind in
                    Label(kind.title, systemImage: kind.symbolName).tag(kind)
                }
            } label: {
                Text(verbatim: String(localized: "make.type", defaultValue: "What to put in the code", bundle: .module))
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier(QRAccessibilityID.makeTypePicker)
        }
    }

    private var previewSection: some View {
        Section {
            VStack(spacing: Tokens.Space.m) {
                if let image = model.previewImage, model.generationError == nil {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 320)
                        .overlay(Rectangle().stroke(Tokens.muted.opacity(0.4), lineWidth: 1))
                        .accessibilityElement()
                        .accessibilityLabel(Text(verbatim: model.codeAccessibilityLabel))
                        .accessibilityAddTraits(.isImage)
                        .accessibilityIdentifier(QRAccessibilityID.makeCodeImage)
                } else if model.isBlank {
                    MakeInlineMessage(text: String(localized: "make.preview.blank",
                                                   defaultValue: "Fill in the fields above and your code appears here.", bundle: .module),
                                      isError: false)
                } else if let issue = firstIssue {
                    MakeInlineMessage(text: issue.message)
                }
                Button {
                    showExport = true
                } label: {
                    Label(String(localized: "make.export", defaultValue: "Export", bundle: .module),
                          systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Tokens.red)
                .disabled(!model.canExport)
                .accessibilityIdentifier(QRAccessibilityID.makeExport)
            }
            .frame(maxWidth: .infinity)
        } header: {
            Text(verbatim: String(localized: "make.preview.header", defaultValue: "Your code", bundle: .module))
                .foregroundStyle(Tokens.muted)
        }
    }

    private var firstIssue: MakeFormIssue? {
        if case .invalid(let issue) = model.outcome, !issue.isBlank { return issue }
        return nil
    }

    private var codeTypeSection: some View {
        Section {
            Picker(selection: $model.symbology) {
                ForEach(MakeSymbology.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
            } label: {
                Text(verbatim: String(localized: "make.symbology", defaultValue: "Code type", bundle: .module))
            }
            .accessibilityIdentifier("make.symbology")
            if model.symbology.supportsCorrection {
                Picker(selection: $model.correction) {
                    ForEach(Correction.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
                } label: {
                    Text(verbatim: String(localized: "make.correction", defaultValue: "Error correction", bundle: .module))
                }
                .accessibilityIdentifier("make.correction")
            }
        } header: {
            Text(verbatim: String(localized: "make.codeType.header", defaultValue: "Code options", bundle: .module))
                .foregroundStyle(Tokens.muted)
        } footer: {
            if model.symbology.supportsCorrection {
                Text(verbatim: String(localized: "make.correction.footer",
                                      defaultValue: "Auto uses medium. Higher levels survive more damage but make a denser code.",
                                      bundle: .module))
                    .foregroundStyle(Tokens.muted)
            }
        }
    }
}
