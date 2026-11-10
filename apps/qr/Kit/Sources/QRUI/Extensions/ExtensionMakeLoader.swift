import Foundation
import UniformTypeIdentifiers

/// Reads the extension's input items into an `ExtensionMakeSource`. Priority: vCard, then web
/// URL, then text, then any other file.
@MainActor
enum ExtensionMakeLoader {
    private struct Box: @unchecked Sendable { let providers: [NSItemProvider] }

    static func load(from context: NSExtensionContext?) async -> ExtensionMakeSource? {
        let items = (context?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        return await source(from: providers)
    }

    static func source(from providers: [NSItemProvider]) async -> ExtensionMakeSource? {
        for provider in providers where conforms(provider, to: .vCard) {
            if let data = await loadData(provider, type: .vCard), data.count <= ExtensionMakeInput.maxTextBytes,
               let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) {
                return .vCard(text)
            }
        }
        for provider in providers where conforms(provider, to: .url) && !conforms(provider, to: .fileURL) {
            if let url = await loadURLString(provider) { return .webURL(url) }
        }
        for provider in providers where conforms(provider, to: .text) || (!conforms(provider, to: .url) && provider.canLoadObject(ofClass: String.self)) {
            if let text = await loadString(provider) { return .text(String(text.prefix(ExtensionMakeInput.maxTextBytes))) }
        }
        if providers.contains(where: { conforms($0, to: .fileURL) || conforms($0, to: .data) || conforms($0, to: .item) }) {
            return .otherFile
        }
        return nil
    }

    private static func conforms(_ provider: NSItemProvider, to type: UTType) -> Bool {
        provider.registeredTypeIdentifiers.contains { UTType($0)?.conforms(to: type) ?? false }
    }

    private static func loadData(_ provider: NSItemProvider, type: UTType) async -> Data? {
        let identifier = provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: type) ?? false } ?? type.identifier
        return await withCheckedContinuation { cont in
            _ = provider.loadDataRepresentation(forTypeIdentifier: identifier) { data, _ in
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
