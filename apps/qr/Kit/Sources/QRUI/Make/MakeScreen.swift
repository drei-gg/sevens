import Combine
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
    @State private var keyboardUp = false

    /// The Make screen; `prefill` comes from a result's "Make code" action. Callers wrap it in
    /// `.gated(feature:)` (paid). Pushed inside a NavigationStack.
    public init(prefill: MakeRequest? = nil) {
        self.prefill = prefill
        _model = State(initialValue: MakeScreenModel(prefill: prefill))
    }

    public var body: some View {
        Form {
            // At accessibility sizes the code would push the fields off screen: it follows them.
            if !typeSize.isAccessibilitySize { previewSection }
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
            if typeSize.isAccessibilitySize { previewSection }
            MakeLinkOptionsSection(model: model)
            codeTypeSection
            MakeStyleSection(foreground: $model.foreground, background: $model.background,
                             warnings: model.contrastWarnings)
        }
        .sevenScreenBackground()
        .safeAreaInset(edge: .bottom, spacing: 0) {
            // Only when there is a code to export, and not over the field being typed in.
            if model.canExport && !keyboardUp { exportBar }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardUp = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardUp = false }
        .scrollDismissesKeyboard(.immediately)
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

    /// The kinds as one row of chips, the selected one filled: one tap to switch, as the
    /// leading code makers do, instead of a menu.
    private var typeSection: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Tokens.Space.s) {
                    ForEach(MakeFormKind.allCases) { kind in
                        let selected = model.kind == kind
                        Button {
                            model.kind = kind
                        } label: {
                            ChipLabel(title: kind.title, systemImage: kind.symbolName).fixedSize()
                        }
                        .modifier(SevenButtonStyle(kind: selected ? .prominent : .bordered))
                        .controlSize(.large)
                        .buttonBorderShape(.capsule)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
                .padding(.horizontal, Tokens.Space.m)
            }
            .sensoryFeedback(.selection, trigger: model.kind)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text(verbatim: String(localized: "make.type", defaultValue: "What to put in the code", bundle: .module)))
            .accessibilityIdentifier(QRAccessibilityID.makeTypePicker)
            .bareListRow()
        }
    }

    /// The hero: the code itself, big on a card, updating as you type.
    private var previewSection: some View {
        Section {
            VStack(spacing: Tokens.Space.m) {
                Group {
                    if let image = model.previewImage, model.generationError == nil {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .accessibilityElement()
                            .accessibilityLabel(Text(verbatim: model.codeAccessibilityLabel))
                            .accessibilityAddTraits(.isImage)
                            .accessibilityIdentifier(QRAccessibilityID.makeCodeImage)
                    } else {
                        Image(systemName: "qrcode")
                            .resizable()
                            .scaledToFit()
                            .padding(Tokens.Space.xl)
                            .foregroundStyle(Tokens.rule)
                            .accessibilityHidden(true)
                    }
                }
                .frame(maxWidth: 240, maxHeight: 240)
                .padding(Tokens.Space.m)
                .background(Tokens.paper, in: RoundedRectangle(cornerRadius: Tokens.Radius.card, style: .continuous))
                if model.isBlank {
                    MakeInlineMessage(text: String(localized: "make.preview.blank.below",
                                                   defaultValue: "Fill in the fields below and your code appears here.", bundle: .module),
                                      isError: false)
                } else if let issue = firstIssue {
                    MakeInlineMessage(text: issue.message)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Tokens.Space.m)
        }
    }

    /// Export, the screen's one primary action, pinned within thumb reach.
    private var exportBar: some View {
        Button {
            showExport = true
        } label: {
            Label(String(localized: "make.export", defaultValue: "Export", bundle: .module),
                  systemImage: "square.and.arrow.up")
                .textRole(.rowTitle, emphasized: true)
                .frame(maxWidth: .infinity)
        }
        .sevenProminentButton()
        .controlSize(.extraLarge)
        .buttonBorderShape(.capsule)
        .accessibilityIdentifier(QRAccessibilityID.makeExport)
        .padding(.horizontal, Tokens.Space.m)
        .padding(.top, Tokens.Space.ms)
        .padding(.bottom, Tokens.Space.s)
        .background(.bar)
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
                                      defaultValue: "Auto keeps the code as small as it can. Higher levels survive more damage but make a denser code.",
                                      bundle: .module))
                    .foregroundStyle(Tokens.muted)
            }
        }
    }
}
