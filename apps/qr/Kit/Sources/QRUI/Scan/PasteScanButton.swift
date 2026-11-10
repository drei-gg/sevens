import QRCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The system paste button (no paste-permission prompt) for images, URLs and plain text.
///
/// - An image is scanned with `ImageScanner`; every code found is delivered.
/// - A URL or text is delivered as one `ScannedCode(text:)` with symbology `.qr` ("analyzed as if
///   scanned").
/// - Nothing usable on the pasteboard, or no code in the image: `onPaste` gets an empty array, so
///   the screen can say "Nothing to scan in what you pasted".
public struct PasteScanButton: View {
    private let onPaste: @MainActor ([ScannedCode]) -> Void

    public init(onPaste: @escaping @MainActor ([ScannedCode]) -> Void) {
        self.onPaste = onPaste
    }

    public var body: some View {
        PasteButton(supportedContentTypes: [.image, .url, .plainText]) { providers in
            let box = ProviderBox(providers: providers)
            let deliver = onPaste
            Task { @MainActor in
                deliver(await Self.codes(from: box.providers))
            }
        }
        .labelStyle(.titleAndIcon)
        .buttonBorderShape(.capsule)
        .accessibilityLabel(String(localized: "scan.paste.a11y", defaultValue: "Paste to scan", bundle: .module))
        .accessibilityHint(String(localized: "scan.paste.hint", defaultValue: "Reads a copied image, link or text and checks it as if it had been scanned.", bundle: .module))
    }

    private struct ProviderBox: @unchecked Sendable { let providers: [NSItemProvider] }

    /// Longest pasted text we accept, in UTF-8 bytes.
    static let maxTextBytes = 64 * 1024

    /// Turns pasteboard items into codes. Images win over URLs, URLs over text.
    @MainActor
    static func codes(from providers: [NSItemProvider]) async -> [ScannedCode] {
        for provider in providers {
            guard let type = provider.registeredTypeIdentifiers
                .compactMap({ UTType($0) }).first(where: { $0.conforms(to: .image) }),
                  let data = await loadData(provider, type: type) else { continue }
            let codes = await ImageScanner().scan(data)
            if !codes.isEmpty { return codes }
        }
        for provider in providers where provider.canLoadObject(ofClass: URL.self) {
            if let url = await loadURLString(provider), let code = textCode(url) { return [code] }
        }
        for provider in providers where provider.canLoadObject(ofClass: String.self) {
            if let text = await loadString(provider), let code = textCode(text) { return [code] }
        }
        return []
    }

    static func textCode(_ text: String) -> ScannedCode? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= maxTextBytes else { return nil }
        return ScannedCode(text: trimmed, symbology: .qr)
    }

    private static func loadData(_ provider: NSItemProvider, type: UTType) async -> Data? {
        await withCheckedContinuation { cont in
            _ = provider.loadDataRepresentation(forTypeIdentifier: type.identifier) { data, _ in
                cont.resume(returning: data)
            }
        }
    }

    private static func loadURLString(_ provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { cont in
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                cont.resume(returning: url?.absoluteString)
            }
        }
    }

    private static func loadString(_ provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { cont in
            _ = provider.loadObject(ofClass: String.self) { string, _ in
                cont.resume(returning: string)
            }
        }
    }
}
