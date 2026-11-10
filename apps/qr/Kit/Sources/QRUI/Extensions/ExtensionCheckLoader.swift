import Foundation
import QRCore
import UniformTypeIdentifiers

/// Loads, scans and analyses the shared images one at a time, so only one is in memory.
@MainActor
enum ExtensionCheckLoader {
    static func providers(from context: NSExtensionContext?) -> [NSItemProvider] {
        let items = (context?.inputItems as? [NSExtensionItem]) ?? []
        let all = items.flatMap { $0.attachments ?? [] }.filter { provider in
            provider.registeredTypeIdentifiers.contains { UTType($0)?.conforms(to: .image) ?? false }
        }
        return Array(all.prefix(ExtensionCheckRules.maxImages))
    }

    static func check(_ provider: NSItemProvider, position: Int) async -> ExtensionCheckImage {
        guard let data = await loadData(provider), !data.isEmpty, data.count <= ExtensionCheckRules.maxImageBytes else {
            return ExtensionCheckImage(position: position, codes: [], unreadable: true)
        }
        let scanned = await ImageScanner().scan(data)
        let analyses = await analyze(Array(scanned.prefix(ExtensionCheckRules.maxCodesPerImage)))
        return ExtensionCheckImage(position: position, codes: analyses.map { ExtensionCheckCode(analysis: $0) }, unreadable: false)
    }

    /// `BundledData.shared` loads here, the first time a code is found (not for empty images).
    @concurrent
    private static func analyze(_ codes: [ScannedCode]) async -> [Analysis] {
        guard !codes.isEmpty else { return [] }
        let analyzer = Analyzer(data: BundledData.shared)
        return codes.map { analyzer.analyze($0) }
    }

    private static func loadData(_ provider: NSItemProvider) async -> Data? {
        guard let identifier = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .image) ?? false })
        else { return nil }
        return await withCheckedContinuation { cont in
            _ = provider.loadDataRepresentation(forTypeIdentifier: identifier) { data, _ in
                cont.resume(returning: data)
            }
        }
    }
}
