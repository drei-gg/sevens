import QRCore
import SevenKit
import SwiftUI
import UIKit

/// The Check this code share extension (SPEC §9). Free forever (D2): no gate here.
/// Shows, per code in up to 5 shared images, the verdict, the reasons, the main field and Copy.
/// No Peek, no Open: nothing leaves the phone and nothing is opened.
public struct CheckExtensionRoot: View {
    let context: NSExtensionContext?

    @State private var results: [ExtensionCheckImage] = []
    @State private var finished = false
    @State private var copiedID: UUID?
    private var preset: [ExtensionCheckImage]?

    public init(context: NSExtensionContext?) {
        self.context = context
    }

    /// Previews and tests: show finished results without loading anything.
    init(previewResults: [ExtensionCheckImage]) {
        self.context = nil
        self.preset = previewResults
        _results = State(initialValue: previewResults)
        _finished = State(initialValue: true)
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Tokens.Space.m) {
                    ForEach(results) { image in
                        imageSection(image)
                    }
                    if !finished {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .accessibilityLabel(String(localized: "ext.check.loading", defaultValue: "Checking", bundle: .module))
                    }
                }
                .padding(Tokens.Space.m)
            }
            .sevenScreenBackground()
            .navigationTitle(String(localized: "ext.check.title", defaultValue: "Check this code", bundle: .module))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done", defaultValue: "Done", bundle: .module)) {
                        ExtensionDismiss.done(context)
                    }
                }
            }
        }
        .sevenLook()
        .task { await run() }
    }

    private var showsImageHeaders: Bool { results.count > 1 }

    @ViewBuilder private func imageSection(_ image: ExtensionCheckImage) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            if showsImageHeaders {
                Text(String(format: String(localized: "ext.check.image", defaultValue: "Image %lld", bundle: .module), image.position))
                    .font(.headline)
                    .foregroundStyle(Tokens.muted)
                    .accessibilityAddTraits(.isHeader)
            }
            if image.unreadable {
                note(String(localized: "ext.check.unreadable", defaultValue: "This image couldn't be read.", bundle: .module))
            } else if image.codes.isEmpty {
                note(String(localized: "ext.check.none", defaultValue: "No code found in this image", bundle: .module))
            } else {
                ForEach(image.codes) { card($0) }
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(.primary)
            .padding(Tokens.Space.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .sevenCard()
    }

    private func card(_ code: ExtensionCheckCode) -> some View {
        let analysis = code.analysis
        let style = VerdictText.style(for: analysis.level)
        let typeName = VerdictText.typeName(for: analysis.payload.typeID)
        let shown = analysis.allSignals.filter { $0.severity != .info }
        return VStack(alignment: .leading, spacing: Tokens.Space.s) {
            HStack(spacing: Tokens.Space.s) {
                Image(systemName: style.symbolName).foregroundStyle(style.color)
                Text(VerdictText.title(for: analysis.level)).foregroundStyle(.primary)
            }
            .font(.title2.weight(.semibold))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(VerdictText.accessibilitySentence(level: analysis.level, typeName: typeName, signals: analysis.allSignals))
            .accessibilityAddTraits(.isHeader)

            Text(typeName).font(.subheadline).foregroundStyle(Tokens.muted)

            ForEach(Array(SignalText.sentences(for: shown).enumerated()), id: \.offset) { _, sentence in
                Text(sentence).font(.body).foregroundStyle(.primary)
            }

            if let summary = code.summary {
                VStack(alignment: .leading, spacing: 2) {
                    Text(summary.label).font(.caption).foregroundStyle(Tokens.muted)
                    Text(summary.value).font(.body).foregroundStyle(.primary)
                }
                .accessibilityElement(children: .combine)
            }

            if let text = code.copyText {
                Button {
                    UIPasteboard.general.string = text
                    copiedID = code.id
                } label: {
                    Label(copiedID == code.id ? String(localized: "ext.copied", defaultValue: "Copied", bundle: .module)
                                              : String(localized: "ext.check.copy", defaultValue: "Copy", bundle: .module),
                          systemImage: copiedID == code.id ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(Tokens.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .sevenCard()
    }

    @MainActor
    private func run() async {
        if preset != nil { return }
        let providers = ExtensionCheckLoader.providers(from: context)
        for (index, provider) in providers.enumerated() {
            results.append(await ExtensionCheckLoader.check(provider, position: index + 1))
        }
        if providers.isEmpty {
            results = [ExtensionCheckImage(position: 1, codes: [], unreadable: false)]
        }
        finished = true
    }
}

